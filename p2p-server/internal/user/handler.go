package user

import (
	"encoding/base64"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

type UserDTO struct {
	ID                string  `json:"id"`
	Username          *string `json:"username"`
	IdentityPublicKey string  `json:"identity_public_key"`
	LastSeen          *string `json:"last_seen"`
	Online            bool    `json:"online"`
}

type SearchUsersResponse struct {
	Users []UserDTO `json:"users"`
}

const minSearchQueryLen = 3

// SearchUser godoc
// @Summary      Поиск пользователей по префиксу username
// @Description  Возвращает до 20 пользователей, чей username начинается с q (регистронезависимо).
// @Description  Минимальная длина q — 3 символа; иначе возвращается пустой список. Себя не включает.
// @Tags         Users
// @Produce      json
// @Security     BearerAuth
// @Param        q    query     string  true  "префикс username (мин. 3 символа)"
// @Success      200  {object}  SearchUsersResponse
// @Failure      401  {object}  map[string]string
// @Failure      500  {object}  map[string]string
// @Router       /users/search [get]
func (h *Handler) SearchUser(c *gin.Context) {
	query := strings.TrimSpace(c.Query("q"))

	// Слишком короткий запрос — не выполняем поиск (защита от перебора каталога).
	if len([]rune(query)) < minSearchQueryLen {
		c.JSON(http.StatusOK, SearchUsersResponse{Users: []UserDTO{}})
		return
	}

	requesterID := c.GetString("user_id")

	users, err := h.service.SearchUsers(c.Request.Context(), query, requesterID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	dtos := make([]UserDTO, 0, len(users))
	for i := range users {
		u := users[i]
		dto := UserDTO{
			ID:                u.ID,
			IdentityPublicKey: base64.StdEncoding.EncodeToString(u.IdentityPublicKey),
		}
		if u.Username.Valid {
			name := u.Username.String
			dto.Username = &name
		}
		if u.LastSeen.Valid {
			lastSeen := u.LastSeen.Time.Format("2006-01-02T15:04:05Z")
			dto.LastSeen = &lastSeen
		}
		dtos = append(dtos, dto)
	}

	c.JSON(http.StatusOK, SearchUsersResponse{Users: dtos})
}

type SetDiscoverableRequest struct {
	Discoverable bool `json:"discoverable"`
}

// SetDiscoverable godoc
// @Summary      Видимость в поиске
// @Description  Включает/выключает отображение текущего пользователя в поиске по username.
// @Tags         Users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      SetDiscoverableRequest  true  "Флаг видимости"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /users/discoverable [put]
func (h *Handler) SetDiscoverable(c *gin.Context) {
	userID := c.GetString("user_id")

	var req SetDiscoverableRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.SetDiscoverable(c.Request.Context(), userID, req.Discoverable); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

type FcmTokenRequest struct {
	Token string `json:"token" binding:"required"`
}

// RegisterFcmToken godoc
// @Summary      Зарегистрировать FCM-токен
// @Description  Привязывает FCM-токен устройства к текущему пользователю (для push-уведомлений).
// @Tags         Users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      FcmTokenRequest  true  "FCM-токен"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /users/fcm-token [put]
func (h *Handler) RegisterFcmToken(c *gin.Context) {
	userID := c.GetString("user_id")

	var req FcmTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.UpsertDeviceToken(c.Request.Context(), userID, req.Token, "android"); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// DeleteFcmToken godoc
// @Summary      Удалить FCM-токен
// @Description  Отвязывает FCM-токен (при выходе из аккаунта).
// @Tags         Users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      FcmTokenRequest  true  "FCM-токен"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /users/fcm-token [delete]
func (h *Handler) DeleteFcmToken(c *gin.Context) {
	var req FcmTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.DeleteDeviceToken(c.Request.Context(), req.Token); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// GetUser godoc
// @Summary      Получить пользователя по ID
// @Description  Возвращает профиль пользователя вместе с его публичным identity-ключом (base64).
// @Tags         Users
// @Produce      json
// @Security     BearerAuth
// @Param        userId  path      string  true  "ID пользователя (UUID)"
// @Success      200     {object}  UserDTO
// @Failure      401     {object}  map[string]string
// @Failure      404     {object}  map[string]string
// @Router       /users/{userId} [get]
func (h *Handler) GetUser(c *gin.Context) {
	userID := c.Param("userId")

	user, err := h.service.GetUser(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	dto := UserDTO{
		ID:                user.ID,
		IdentityPublicKey: base64.StdEncoding.EncodeToString(user.IdentityPublicKey),
		Online:            user.Online,
	}

	if user.Username.Valid {
		dto.Username = &user.Username.String
	}

	if user.LastSeen.Valid {
		lastSeen := user.LastSeen.Time.Format("2006-01-02T15:04:05Z")
		dto.LastSeen = &lastSeen
	}

	c.JSON(http.StatusOK, dto)
}
