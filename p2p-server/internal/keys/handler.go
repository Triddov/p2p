package keys

import (
	"encoding/base64"
	"net/http"

	"github.com/Triddov/p2p-server/internal/models"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

// Request / Response types

type OneTimePrekeyDTO struct {
	ID        int    `json:"id" binding:"required"`
	PublicKey string `json:"public_key" binding:"required"` // base64
}

type RegisterPrekeysRequest struct {
	RegistrationID  int                `json:"registration_id" binding:"required"`
	IdentityKey     string             `json:"identity_key" binding:"required"` // base64
	SignedPrekeyID  int                `json:"signed_prekey_id" binding:"required"`
	SignedPrekey    string             `json:"signed_prekey" binding:"required"`           // base64
	SignedPrekeySig string             `json:"signed_prekey_signature" binding:"required"` // base64
	OneTimePrekeys  []OneTimePrekeyDTO `json:"one_time_prekeys"`
}

type PrekeyBundleResponse struct {
	RegistrationID  int    `json:"registration_id"`
	IdentityKey     string `json:"identity_key"`
	SignedPrekeyID  int    `json:"signed_prekey_id"`
	SignedPrekey    string `json:"signed_prekey"`
	SignedPrekeySig string `json:"signed_prekey_signature"`
	OneTimePrekeyID *int   `json:"one_time_prekey_id,omitempty"`
	OneTimePrekey   string `json:"one_time_prekey,omitempty"`
}

type OTKCountResponse struct {
	Count int `json:"count"`
}

// Handlers

// RegisterPrekeys загружает signed prekey и пакет OTK на сервер.
// Вызывается при первом запуске и при ротации/пополнении.
func (h *Handler) RegisterPrekeys(c *gin.Context) {
	userID := c.GetString("user_id")

	var req RegisterPrekeysRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	identityKey, err := base64.StdEncoding.DecodeString(req.IdentityKey)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid identity_key encoding"})
		return
	}
	signedPrekey, err := base64.StdEncoding.DecodeString(req.SignedPrekey)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid signed_prekey encoding"})
		return
	}
	signedPrekeySig, err := base64.StdEncoding.DecodeString(req.SignedPrekeySig)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid signed_prekey_signature encoding"})
		return
	}

	oneTimePrekeys := make([]models.OneTimePrekey, 0, len(req.OneTimePrekeys))
	for _, dto := range req.OneTimePrekeys {
		pk, err := base64.StdEncoding.DecodeString(dto.PublicKey)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid one_time_prekey encoding"})
			return
		}
		oneTimePrekeys = append(oneTimePrekeys, models.OneTimePrekey{
			UserID:    userID,
			PrekeyID:  dto.ID,
			PublicKey: pk,
		})
	}

	if err := h.service.RegisterPrekeys(
		c.Request.Context(),
		userID,
		req.RegistrationID,
		identityKey,
		signedPrekey,
		signedPrekeySig,
		req.SignedPrekeyID,
		oneTimePrekeys,
	); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// GetPrekeyBundle возвращает prekey bundle собеседника для установки X3DH сессии.
// Атомарно расходует один OTK из пула.
func (h *Handler) GetPrekeyBundle(c *gin.Context) {
	targetUserID := c.Param("userId")

	bundle, err := h.service.GetPrekeyBundle(c.Request.Context(), targetUserID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	resp := PrekeyBundleResponse{
		RegistrationID:  bundle.RegistrationID,
		IdentityKey:     base64.StdEncoding.EncodeToString(bundle.IdentityKey),
		SignedPrekeyID:  bundle.SignedPrekeyID,
		SignedPrekey:    base64.StdEncoding.EncodeToString(bundle.SignedPrekey),
		SignedPrekeySig: base64.StdEncoding.EncodeToString(bundle.SignedPrekeySig),
	}
	if bundle.OneTimePrekeyID != nil {
		resp.OneTimePrekeyID = bundle.OneTimePrekeyID
		resp.OneTimePrekey = base64.StdEncoding.EncodeToString(bundle.OneTimePrekey)
	}

	c.JSON(http.StatusOK, resp)
}

// GetOTKCount возвращает количество оставшихся OTK текущего пользователя.
func (h *Handler) GetOTKCount(c *gin.Context) {
	userID := c.GetString("user_id")

	count, err := h.service.GetOTKCount(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, OTKCountResponse{Count: count})
}
