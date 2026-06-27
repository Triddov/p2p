package signaling

import (
	"log"
	"net/http"

	jwtpkg "github.com/Triddov/p2p-server/pkg/jwt"
	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

type Handler struct {
	hub       *Hub
	jwtSecret string
	upgrader  websocket.Upgrader
}

func NewHandler(hub *Hub, jwtSecret string, allowedOrigins []string) *Handler {
	allowed := make(map[string]bool, len(allowedOrigins))
	allowAll := false
	for _, o := range allowedOrigins {
		if o == "*" {
			allowAll = true
		}
		allowed[o] = true
	}

	return &Handler{
		hub:       hub,
		jwtSecret: jwtSecret,
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				origin := r.Header.Get("Origin")
				if origin == "" {
					return true
				}
				return allowAll || allowed[origin]
			},
		},
	}
}

func (h *Handler) HandleWebSocket(c *gin.Context) {
	// Аутентификация через query параметр
	token := c.Query("token")
	if token == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "token required"})
		return
	}

	claims, err := jwtpkg.ValidateToken(token, h.jwtSecret)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
		return
	}

	// Апгрейд до WebSocket с http 101
	conn, err := h.upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Printf("Failed to upgrade connection: %v", err)
		return
	}

	client := &Client{
		hub:    h.hub,
		conn:   conn,
		send:   make(chan []byte, 256),
		userID: claims.UserID,
	}

	client.hub.register <- client

	// горутины для чтения/записи
	go client.writePump()
	go client.readPump()
}
