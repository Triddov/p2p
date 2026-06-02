package signaling

import (
	"log"
	"net/http"

	jwtpkg "github.com/Triddov/p2p-server/pkg/jwt"
	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // todo
	},
}

type Handler struct {
	hub       *Hub
	jwtSecret string
}

func NewHandler(hub *Hub, jwtSecret string) *Handler {
	return &Handler{
		hub:       hub,
		jwtSecret: jwtSecret,
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
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
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
