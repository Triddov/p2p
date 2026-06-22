package user

import (
	"encoding/base64"
	"net/http"

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

type SearchUserResponse struct {
	Found bool     `json:"found"`
	User  *UserDTO `json:"user,omitempty"`
}

// SearchUser godoc
// @Summary      Поиск пользователя по username
// @Description  Возвращает пользователя по точному username. Поле found=false, если не найден.
// @Tags         Users
// @Produce      json
// @Security     BearerAuth
// @Param        q    query     string  true  "username для поиска"
// @Success      200  {object}  SearchUserResponse
// @Failure      400  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      500  {object}  map[string]string
// @Router       /users/search [get]
func (h *Handler) SearchUser(c *gin.Context) {
	username := c.Query("q")
	if username == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username query required"})
		return
	}

	user, err := h.service.SearchUser(c.Request.Context(), username)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if user == nil {
		c.JSON(http.StatusOK, SearchUserResponse{Found: false})
		return
	}

	dto := &UserDTO{
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

	c.JSON(http.StatusOK, SearchUserResponse{
		Found: true,
		User:  dto,
	})
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
