package models

import (
	"database/sql"
	"time"
)

// User - представление пользователя
type User struct {
	ID                string         `json:"id" db:"id"`
	Email             string         `json:"email" db:"email"`
	Username          sql.NullString `json:"username" db:"username"`
	IdentityPublicKey []byte         `json:"identity_public_key" db:"identity_public_key"`
	CreatedAt         time.Time      `json:"created_at" db:"created_at"`
	LastSeen          sql.NullTime   `json:"last_seen" db:"last_seen"`
	Online            bool           `json:"online" db:"online"`
	DeviceID          sql.NullString `json:"device_id" db:"device_id"`
}

// PendingMessage - представление сообщение для оффлайн доставки
type PendingMessage struct {
	ID             string         `json:"id" db:"id"`
	SenderID       string         `json:"sender_id" db:"sender_id"`
	RecipientID    string         `json:"recipient_id" db:"recipient_id"`
	Ciphertext     []byte         `json:"ciphertext" db:"ciphertext"`
	MessageType    int16          `json:"message_type" db:"message_type"`
	RatchetIndex   int64          `json:"ratchet_index" db:"ratchet_index"`
	InitialMessage sql.NullString `json:"initial_message" db:"initial_message"`
	Timestamp      time.Time      `json:"timestamp" db:"timestamp"`
	Delivered      bool           `json:"delivered" db:"delivered"`
	DeliveredAt    sql.NullTime   `json:"delivered_at" db:"delivered_at"`
}

// SignedPrekey — активный signed prekey пользователя
type SignedPrekey struct {
	UserID    string    `db:"user_id"`
	PrekeyID  int       `db:"prekey_id"`
	PublicKey []byte    `db:"public_key"`
	Signature []byte    `db:"signature"`
	CreatedAt time.Time `db:"created_at"`
}

// OneTimePrekey — одноразовый prekey из пула
type OneTimePrekey struct {
	ID        string `db:"id"`
	UserID    string `db:"user_id"`
	PrekeyID  int    `db:"prekey_id"`
	PublicKey []byte `db:"public_key"`
}

// PrekeyBundle — данные для установки X3DH сессии
type PrekeyBundle struct {
	RegistrationID  int
	IdentityKey     []byte
	SignedPrekeyID  int
	SignedPrekey    []byte
	SignedPrekeySig []byte
	OneTimePrekeyID *int
	OneTimePrekey   []byte
}

// EmailVerificationCode для верификации email
type EmailVerificationCode struct {
	Email     string    `db:"email"`
	Code      string    `db:"code"`
	Attempts  int       `db:"attempts"`
	CreatedAt time.Time `db:"created_at"`
	ExpiresAt time.Time `db:"expires_at"`
}

// SignalingSession - представление активного WebSocket соединения
type SignalingSession struct {
	UserID       string    `db:"user_id"`
	ConnectionID string    `db:"connection_id"`
	ConnectedAt  time.Time `db:"connected_at"`
	LastPing     time.Time `db:"last_ping"`
	ServerNode   string    `db:"server_node"`
}

// AuditLog для логирования действий
type AuditLog struct {
	ID           int64          `json:"id" db:"id"`
	UserID       sql.NullString `json:"user_id" db:"user_id"`
	Action       string         `json:"action" db:"action"`
	ResourceType sql.NullString `json:"resource_type" db:"resource_type"`
	ResourceID   sql.NullString `json:"resource_id" db:"resource_id"`
	IPAddress    sql.NullString `json:"ip_address" db:"ip_address"`
	UserAgent    sql.NullString `json:"user_agent" db:"user_agent"`
	Metadata     sql.NullString `json:"metadata" db:"metadata"`
	CreatedAt    time.Time      `json:"created_at" db:"created_at"`
}
