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

// decodeB64 декодирует base64-поле; при ошибке - 400 и ok=false.
func decodeB64(c *gin.Context, value, field string) ([]byte, bool) {
	b, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid " + field + " encoding"})
		return nil, false
	}
	return b, true
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


// RegisterPrekeys godoc
// @Summary      Загрузить prekey-бандл
// @Description  Загружает signed prekey и пакет one-time prekeys текущего пользователя.
// @Description  Все ключи передаются в base64. Вызывается при первом запуске и при пополнении пула.
// @Tags         Keys
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      RegisterPrekeysRequest  true  "Identity-ключ, signed prekey и OTK"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /keys/prekeys [put]
func (h *Handler) RegisterPrekeys(c *gin.Context) {
	userID := c.GetString("user_id")

	var req RegisterPrekeysRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	identityKey, ok := decodeB64(c, req.IdentityKey, "identity_key")
	if !ok {
		return
	}
	signedPrekey, ok := decodeB64(c, req.SignedPrekey, "signed_prekey")
	if !ok {
		return
	}
	signedPrekeySig, ok := decodeB64(c, req.SignedPrekeySig, "signed_prekey_signature")
	if !ok {
		return
	}

	oneTimePrekeys := make([]models.OneTimePrekey, 0, len(req.OneTimePrekeys))
	for _, dto := range req.OneTimePrekeys {
		pk, ok := decodeB64(c, dto.PublicKey, "one_time_prekey")
		if !ok {
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

type UpdateSignedPrekeyRequest struct {
	SignedPrekeyID  int    `json:"signed_prekey_id" binding:"required"`
	SignedPrekey    string `json:"signed_prekey" binding:"required"`
	SignedPrekeySig string `json:"signed_prekey_signature" binding:"required"`
}

// UpdateSignedPrekey godoc
// @Summary      Ротация signed prekey
// @Description  Заменяет signed prekey текущего пользователя (периодическая ротация). Ключи — base64.
// @Tags         Keys
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      UpdateSignedPrekeyRequest  true  "Новый signed prekey"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /keys/signed-prekey [put]
func (h *Handler) UpdateSignedPrekey(c *gin.Context) {
	userID := c.GetString("user_id")

	var req UpdateSignedPrekeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	pub, ok := decodeB64(c, req.SignedPrekey, "signed_prekey")
	if !ok {
		return
	}
	sig, ok := decodeB64(c, req.SignedPrekeySig, "signed_prekey_signature")
	if !ok {
		return
	}

	if err := h.service.UpdateSignedPrekey(c.Request.Context(), userID, req.SignedPrekeyID, pub, sig); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

type AddOneTimePrekeysRequest struct {
	OneTimePrekeys []OneTimePrekeyDTO `json:"one_time_prekeys" binding:"required"`
}

// AddOneTimePrekeys godoc
// @Summary      Дозалить one-time prekeys
// @Description  Пополняет пул OTK текущего пользователя (когда пул на сервере истощается).
// @Tags         Keys
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      AddOneTimePrekeysRequest  true  "Новые OTK (base64)"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /keys/otks [post]
func (h *Handler) AddOneTimePrekeys(c *gin.Context) {
	userID := c.GetString("user_id")

	var req AddOneTimePrekeysRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	otks := make([]models.OneTimePrekey, 0, len(req.OneTimePrekeys))
	for _, dto := range req.OneTimePrekeys {
		pk, ok := decodeB64(c, dto.PublicKey, "one_time_prekey")
		if !ok {
			return
		}
		otks = append(otks, models.OneTimePrekey{
			UserID:    userID,
			PrekeyID:  dto.ID,
			PublicKey: pk,
		})
	}

	if err := h.service.AddOneTimePrekeys(c.Request.Context(), userID, otks); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// GetPrekeyBundle godoc
// @Summary      Получить prekey-бандл собеседника
// @Description  Возвращает prekey-бандл указанного пользователя для установки X3DH-сессии.
// @Description  Атомарно расходует один OTK из его пула (если есть). Ключи — в base64.
// @Tags         Keys
// @Produce      json
// @Security     BearerAuth
// @Param        userId  path      string  true  "ID пользователя (UUID)"
// @Success      200     {object}  PrekeyBundleResponse
// @Failure      401     {object}  map[string]string
// @Failure      404     {object}  map[string]string
// @Router       /keys/{userId} [get]
//
// GetPrekeyBundle возвращает prekey bundle собеседника для установки X3DH сессии.
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

// GetOTKCount godoc
// @Summary      Количество оставшихся OTK
// @Description  Возвращает число неизрасходованных one-time prekeys текущего пользователя.
// @Description  Клиент использует это для своевременного пополнения пула.
// @Tags         Keys
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  OTKCountResponse
// @Failure      401  {object}  map[string]string
// @Failure      500  {object}  map[string]string
// @Router       /keys/count [get]
func (h *Handler) GetOTKCount(c *gin.Context) {
	userID := c.GetString("user_id")

	count, err := h.service.GetOTKCount(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, OTKCountResponse{Count: count})
}
