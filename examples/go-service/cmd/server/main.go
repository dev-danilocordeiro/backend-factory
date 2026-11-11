// Command server runs the order service.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"example.com/go-service/internal/httpapi"
	"example.com/go-service/internal/order"
	"example.com/go-service/internal/store"
)

func main() {
	addr := os.Getenv("ADDR")
	if addr == "" {
		addr = ":8080"
	}
	srv := &http.Server{
		Addr:              addr,
		Handler:           httpapi.NewHandler(order.NewService(store.NewMemory())),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}
