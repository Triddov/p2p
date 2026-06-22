package message

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

type InitialMessageDTO struct {
    SenderIdentityKey  string `json:"sender_identity_key"`
    SenderEphemeralKey string `json:"sender_ephemeral_key"`
}

type StoreMessageRequest struct {
    RecipientID    string             `json:"recipient_id" binding:"required"`
    SenderID       string             `json:"sender_id" binding:"required"`
    MessageID      string             `json:"message_id" binding:"required"`
    Timestamp      int64              `json:"timestamp" binding:"required"`
    Ciphertext     string             `json:"ciphertext" binding:"required"`
    MessageType    int16              `json:"message_type"`
    RatchetIndex   int64              `json:"ratchet_index"`
    InitialMessage *InitialMessageDTO `json:"initial_message"`
}

type StoreMessageResponse struct {
    Status     string `json:"status"`
    MessageID  string `json:"message_id"`
    WillNotify bool   `json:"will_notify"`
}

// StoreMessage godoc
// @Summary      Сохранить сообщение для оффлайн-доставки
// @Description  Кладёт E2EE-зашифрованное сообщение в очередь получателя. Сервер не может его расшифровать.
// @Description  ciphertext передаётся в base64. Используется, когда получатель недоступен по P2P.
// @Tags         Messages
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      StoreMessageRequest  true  "Зашифрованное сообщение"
// @Success      200      {object}  StoreMessageResponse
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /messages/store [post]
func (h *Handler) StoreMessage(c *gin.Context) {
    senderID := c.GetString("user_id")

    var req StoreMessageRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    // Декодируем ciphertext из base64
    ciphertext, err := base64.StdEncoding.DecodeString(req.Ciphertext)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid ciphertext encoding"})
        return
    }

    req.Ciphertext = string(ciphertext)

    if err := h.service.StoreMessage(c.Request.Context(), &req, senderID); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    c.JSON(http.StatusOK, StoreMessageResponse{
        Status:     "stored",
        MessageID:  req.MessageID,
        WillNotify: true,
    })
}

type PendingMessageDTO struct {
    ID          string `json:"id"`
    SenderID    string `json:"sender_id"`
    Ciphertext  string `json:"ciphertext"`
    MessageType int16  `json:"message_type"`
    Timestamp   int64  `json:"timestamp"`
}

type PendingMessagesResponse struct {
    Messages []PendingMessageDTO `json:"messages"`
}

// GetPendingMessages godoc
// @Summary      Получить недоставленные сообщения
// @Description  Возвращает до 100 ожидающих сообщений текущего пользователя. ciphertext — в base64.
// @Description  После сохранения на клиенте их следует подтвердить через /messages/ack.
// @Tags         Messages
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  PendingMessagesResponse
// @Failure      401  {object}  map[string]string
// @Failure      500  {object}  map[string]string
// @Router       /messages/pending [get]
func (h *Handler) GetPendingMessages(c *gin.Context) {
    recipientID := c.GetString("user_id")

    messages, err := h.service.GetPendingMessages(c.Request.Context(), recipientID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    // кодирование ciphertext в base64
    for i := range messages {
        messages[i].Ciphertext = base64.StdEncoding.EncodeToString([]byte(messages[i].Ciphertext))
    }

    c.JSON(http.StatusOK, PendingMessagesResponse{Messages: messages})
}

type AckMessagesRequest struct {
    MessageIDs []string `json:"message_ids" binding:"required"`
}

// AckMessages godoc
// @Summary      Подтвердить доставку сообщений
// @Description  Помечает перечисленные сообщения как доставленные, чтобы они больше не выдавались.
// @Tags         Messages
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        request  body      AckMessagesRequest  true  "Список ID сообщений"
// @Success      200      {object}  map[string]string
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Failure      500      {object}  map[string]string
// @Router       /messages/ack [post]
func (h *Handler) AckMessages(c *gin.Context) {
    recipientID := c.GetString("user_id")

    var req AckMessagesRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    if err := h.service.AckMessages(c.Request.Context(), req.MessageIDs, recipientID); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    c.JSON(http.StatusOK, gin.H{"status": "ack"})
}