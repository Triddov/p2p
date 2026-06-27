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
