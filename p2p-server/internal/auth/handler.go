package auth

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

type RequestCodeRequest struct {
	Email    string `json:"email" binding:"required,email"`
	DeviceID string `json:"device_id" binding:"required"`
}

type RequestCodeResponse struct {
	Status     string `json:"status"`
	RetryAfter *int   `json:"retry_after,omitempty"`
}

// RequestCode godoc
// @Summary      Запросить код подтверждения
// @Description  Отправляет 6-значный код на email. Действует ограничение по частоте отправки.
// @Tags         Auth
// @Accept       json
// @Produce      json
// @Param        request  body      RequestCodeRequest  true  "Email и идентификатор устройства"
// @Success      200      {object}  RequestCodeResponse
// @Failure      400      {object}  map[string]string
// @Failure      429      {object}  map[string]string
// @Router       /auth/request-code [post]
func (h *Handler) RequestCode(c *gin.Context) {
	var req RequestCodeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.RequestVerificationCode(c.Request.Context(), req.Email, req.DeviceID); err != nil {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": err.Error()})
		return
	}

	retryAfter := 60
	c.JSON(http.StatusOK, RequestCodeResponse{
		Status:     "code_sent",
		RetryAfter: &retryAfter,
	})
}

type VerifyCodeRequest struct {
	Email             string `json:"email" binding:"required,email"`
	Code              string `json:"code" binding:"required,len=6"`
	DeviceID          string `json:"device_id" binding:"required"`
	IdentityPublicKey string `json:"identity_public_key" binding:"required"`
}

type VerifyCodeResponse struct {
	UserID            string `json:"user_id"`
	Email             string `json:"email"`
	AccessToken       string `json:"access_token"`
	RefreshToken      string `json:"refresh_token"`
	IdentityPublicKey string `json:"identity_public_key"`
}

// VerifyCode godoc
// @Summary      Подтвердить код и зарегистрироваться/войти
// @Description  Проверяет код, при первом входе регистрирует пользователя с его identity-ключом.
// @Description  Возвращает пару токенов (access + refresh).
// @Tags         Auth
// @Accept       json
// @Produce      json
// @Param        request  body      VerifyCodeRequest  true  "Код, email, устройство и публичный identity-ключ (base64)"
// @Success      200      {object}  VerifyCodeResponse
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Router       /auth/verify [post]
func (h *Handler) VerifyCode(c *gin.Context) {
	var req VerifyCodeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user, accessToken, refreshToken, err := h.service.VerifyCodeAndRegister(
		c.Request.Context(),
		req.Email,
		req.Code,
		req.DeviceID,
		req.IdentityPublicKey,
	)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, VerifyCodeResponse{
		UserID:            user.ID,
		Email:             user.Email,
		AccessToken:       accessToken,
		RefreshToken:      refreshToken,
		IdentityPublicKey: req.IdentityPublicKey,
	})
}

type RefreshTokenRequest struct {
	RefreshToken string `json:"refresh_token" binding:"required"`
}

type RefreshTokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
}

// RefreshToken godoc
// @Summary      Обновить пару токенов
// @Description  Принимает refresh-токен, ротирует его и возвращает новую пару access + refresh.
// @Tags         Auth
// @Accept       json
// @Produce      json
// @Param        request  body      RefreshTokenRequest  true  "Действующий refresh-токен"
// @Success      200      {object}  RefreshTokenResponse
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Router       /auth/refresh [post]
func (h *Handler) RefreshToken(c *gin.Context) {
	var req RefreshTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	accessToken, refreshToken, err := h.service.RefreshToken(c.Request.Context(), req.RefreshToken)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, RefreshTokenResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
	})
}

type SetUsernameRequest struct {
	Username string `json:"username" binding:"required,min=3,max=32"`
}

type SetUsernameResponse struct {
	Username string `json:"username"`
}

// SetUsername godoc
// @Summary      Установить username
// @Description  Задаёт уникальный username текущего пользователя (3–32 символа). Обязателен после регистрации.
// @Tags         Users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      SetUsernameRequest  true  "Желаемый username"
// @Success      200      {object}  SetUsernameResponse
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Router       /users/set-username [post]
func (h *Handler) SetUsername(c *gin.Context) {
	userID := c.GetString("user_id")

	var req SetUsernameRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.SetUsername(c.Request.Context(), userID, req.Username); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, SetUsernameResponse{Username: req.Username})
}
