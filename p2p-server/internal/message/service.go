package message

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/lib/pq"
	"github.com/Triddov/p2p-server/internal/database"
)

type Notifier interface {
	NotifyNewMessage(ctx context.Context, recipientID string)
}

type Service struct {
	db       *database.DB
	notifier Notifier // может быть nil (пуши отключены)
}

func NewService(db *database.DB, notifier Notifier) *Service {
	return &Service{db: db, notifier: notifier}
}

// StoreMessage сохраняет сообщение для оффлайн доставки
func (s *Service) StoreMessage(ctx context.Context, msg *StoreMessageRequest, senderID string) error {
	var initialMessageJSON []byte
	var err error

	if msg.InitialMessage != nil {
		initialMessageJSON, err = json.Marshal(msg.InitialMessage)
		if err != nil {
			return fmt.Errorf("failed to marshal initial message: %w", err)
		}
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO pending_messages
         (id, sender_id, recipient_id, ciphertext, message_type, ratchet_index, initial_message, timestamp)
         VALUES ($1, $2, $3, $4, $5, $6, $7, to_timestamp($8))`,
		msg.MessageID,
		senderID,
		msg.RecipientID,
		[]byte(msg.Ciphertext),
		msg.MessageType,
		msg.RatchetIndex,
		initialMessageJSON,
		float64(msg.Timestamp)/1000.0,
	)

	if err != nil {
		return fmt.Errorf("failed to store message: %w", err)
	}

	// Будим получателя пушем (асинхронно - не блокируем ответ)
	if s.notifier != nil {
		recipientID := msg.RecipientID
		go s.notifier.NotifyNewMessage(context.Background(), recipientID)
	}

	return nil
}

// GetPendingMessages получает непрочитанные сообщения для пользователя
func (s *Service) GetPendingMessages(ctx context.Context, recipientID string) ([]PendingMessageDTO, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, sender_id, ciphertext, message_type,
                (EXTRACT(EPOCH FROM timestamp) * 1000)::bigint as timestamp
         FROM pending_messages
         WHERE recipient_id = $1 AND delivered = FALSE
         ORDER BY timestamp ASC
         LIMIT 100`,
		recipientID,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to query messages: %w", err)
	}
	defer rows.Close()

	var messages []PendingMessageDTO
	for rows.Next() {
		var msg PendingMessageDTO
		var ciphertext []byte
		err := rows.Scan(
			&msg.ID,
			&msg.SenderID,
			&ciphertext,
			&msg.MessageType,
			&msg.Timestamp,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan message: %w", err)
		}

		// ciphertext хранится в BYTEA как сырые байты; кодирование в base64
		msg.Ciphertext = string(ciphertext)

		messages = append(messages, msg)
	}

	return messages, nil
}

// AckMessages помечает сообщения как доставленные
func (s *Service) AckMessages(ctx context.Context, messageIDs []string, recipientID string) error {
	if len(messageIDs) == 0 {
		return nil
	}

	// Конвертируем в массив UUID для PostgreSQL
	query := `
        UPDATE pending_messages
        SET delivered = TRUE, delivered_at = NOW()
        WHERE id = ANY($1::UUID[]) AND recipient_id = $2
    `

	_, err := s.db.ExecContext(ctx, query, pq.Array(messageIDs), recipientID)
	if err != nil {
		return fmt.Errorf("failed to ack messages: %w", err)
	}

	return nil
}
