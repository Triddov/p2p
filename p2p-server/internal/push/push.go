package push

import (
	"context"
	"log"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

// TokenStore — источник FCM-токенов получателя и удаление невалидных.
type TokenStore interface {
	GetDeviceTokens(ctx context.Context, userID string) ([]string, error)
	DeleteDeviceToken(ctx context.Context, token string) error
}

type Notifier struct {
	client *messaging.Client
	tokens TokenStore
}

// NewNotifier инициализирует Firebase Admin из service-account по пути credsPath.
func NewNotifier(ctx context.Context, credsPath string, tokens TokenStore) (*Notifier, error) {
	app, err := firebase.NewApp(ctx, nil, option.WithCredentialsFile(credsPath))
	if err != nil {
		return nil, err
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, err
	}
	return &Notifier{client: client, tokens: tokens}, nil
}

// NotifyNewMessage шлёт получателю data-push «есть новое сообщение» (без содержимого —
// сервер не может расшифровать; клиент сам подтянет pending и покажет уведомление).
func (n *Notifier) NotifyNewMessage(ctx context.Context, recipientID string) {
	tokens, err := n.tokens.GetDeviceTokens(ctx, recipientID)
	if err != nil || len(tokens) == 0 {
		return
	}

	msg := &messaging.MulticastMessage{
		Tokens: tokens,
		Data:   map[string]string{"type": "new_message"},
		Android: &messaging.AndroidConfig{
			Priority: "high",
		},
	}

	resp, err := n.client.SendEachForMulticast(ctx, msg)
	if err != nil {
		log.Printf("push: send failed: %v", err)
		return
	}

	// Чистим невалидные/отозванные токены.
	for i, r := range resp.Responses {
		if !r.Success && (messaging.IsUnregistered(r.Error) || messaging.IsInvalidArgument(r.Error)) {
			_ = n.tokens.DeleteDeviceToken(ctx, tokens[i])
		}
	}
}
