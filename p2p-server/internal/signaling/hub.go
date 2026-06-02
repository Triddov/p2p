package signaling

import (
	"context"
	"encoding/json"
	"log"

	"github.com/Triddov/p2p-server/internal/database"
)

type Hub struct {
	clients    map[string]*Client
	broadcast  chan *SignalMessage
	register   chan *Client
	unregister chan *Client
	db         *database.DB
}

func NewHub(db *database.DB) *Hub {
	return &Hub{
		clients:    make(map[string]*Client),
		broadcast:  make(chan *SignalMessage),
		register:   make(chan *Client),
		unregister: make(chan *Client),
		db:         db,
	}
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.clients[client.userID] = client
			log.Printf("Client connected: %s", client.userID)

			// сохраниние сессии в БД
			h.saveSession(client.userID)

		case client := <-h.unregister:
			if _, ok := h.clients[client.userID]; ok {
				delete(h.clients, client.userID)
				close(client.send)
				log.Printf("Client disconnected: %s", client.userID)

				// удаление сессии из БД
				h.deleteSession(client.userID)
			}

		case message := <-h.broadcast:
			h.handleMessage(message)
		}
	}
}

func (h *Hub) handleMessage(msg *SignalMessage) {
	// Находим получателя
	recipient, ok := h.clients[msg.To]
	if !ok {
		// получатель оффлайн - отправляем ошибку отправителю
		sender, ok := h.clients[msg.From]
		if ok {
			errorMsg := SignalMessage{
				Type:  "error",
				From:  "server",
				To:    msg.From,
				Error: "peer_offline",
			}
			data, _ := json.Marshal(errorMsg)
			select {
			case sender.send <- data:
			default:
				close(sender.send)
				delete(h.clients, msg.From)
			}
		}
		return
	}

	// Пересылаем сообщение получателю
	data, err := json.Marshal(msg)
	if err != nil {
		log.Printf("Failed to marshal message: %v", err)
		return
	}

	select {
	case recipient.send <- data:
	default:
		close(recipient.send)
		delete(h.clients, msg.To)
	}
}

func (h *Hub) saveSession(userID string) {
	ctx := context.Background()
	_, err := h.db.ExecContext(ctx,
		`INSERT INTO signaling_sessions (user_id, connection_id, server_node)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE
         SET connected_at = NOW(), last_ping = NOW()`,
		userID, userID, "node-1",
	)
	if err != nil {
		log.Printf("Failed to save session: %v", err)
	}
}

func (h *Hub) deleteSession(userID string) {
	ctx := context.Background()
	_, err := h.db.ExecContext(ctx,
		"DELETE FROM signaling_sessions WHERE user_id = $1",
		userID,
	)
	if err != nil {
		log.Printf("Failed to delete session: %v", err)
	}
}

type SignalMessage struct {
	Type          string            `json:"type"`
	To            string            `json:"to,omitempty"`
	From          string            `json:"from,omitempty"`
	SDP           string            `json:"sdp,omitempty"`
	IceCandidate  *IceCandidateDTO  `json:"iceCandidate,omitempty"`
	IceCandidates []IceCandidateDTO `json:"iceCandidates,omitempty"`
	Error         string            `json:"error,omitempty"`
}

type IceCandidateDTO struct {
	SdpMid        string `json:"sdpMid"`
	SdpMLineIndex int    `json:"sdpMLineIndex"`
	Candidate     string `json:"candidate"`
}
