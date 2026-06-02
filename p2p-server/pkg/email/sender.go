package email

import (
	"crypto/tls"
	"fmt"

	"gopkg.in/gomail.v2"
)

type Sender struct {
	host          string
	port          int
	username      string
	password      string
	from          string
	tlsServerName string
}

func NewSender(host string, port int, username, password, from, tlsServerName string) *Sender {
	return &Sender{
		host:          host,
		port:          port,
		username:      username,
		password:      password,
		from:          from,
		tlsServerName: tlsServerName,
	}
}

func (s *Sender) SendVerificationCode(to, code string) error {
	m := gomail.NewMessage()
	m.SetHeader("From", s.from)
	m.SetHeader("To", to)
	m.SetHeader("Subject", "Your P2P Messenger Verification Code")

	body := fmt.Sprintf(`
        <html>
        <body>
            <h2>P2P Messenger Verification</h2>
            <p>Verification code is:</p>
            <h1 style="color: #4CAF50;">%s</h1>
            <p>This code will expire in 5 minutes.</p>
            <p>If you didn't request this code, please just ignore this email.</p>
        </body>
        </html>
    `, code)

	m.SetBody("text/html", body)

	d := gomail.NewDialer(s.host, s.port, s.username, s.password)
	if s.tlsServerName != "" {
		d.TLSConfig = &tls.Config{ServerName: s.tlsServerName}
	}

	return d.DialAndSend(m)
}
