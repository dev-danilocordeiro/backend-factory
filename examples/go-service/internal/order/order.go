// Package order holds the order domain and its use cases.
package order

import (
	"context"
	"errors"
	"strings"
)

// Order is a customer order for one SKU.
type Order struct {
	ID       string `json:"id"`
	Customer string `json:"customer"`
	SKU      string `json:"sku"`
	Quantity int    `json:"quantity"`
}

// ErrInvalid is returned when an order fails validation.
var ErrInvalid = errors.New("invalid order")

// ErrNotFound is returned when an order does not exist.
var ErrNotFound = errors.New("order not found")

// Repository persists orders. Implementations live in the store package.
type Repository interface {
	Save(ctx context.Context, o Order) (Order, error)
	Get(ctx context.Context, id string) (Order, error)
	List(ctx context.Context) ([]Order, error)
}

// Service implements the order use cases.
type Service struct {
	repo Repository
}

// NewService returns a Service backed by repo.
func NewService(repo Repository) *Service { return &Service{repo: repo} }

// Create validates and stores a new order.
func (s *Service) Create(ctx context.Context, o Order) (Order, error) {
	if strings.TrimSpace(o.SKU) == "" || strings.TrimSpace(o.Customer) == "" || o.Quantity < 1 {
		return Order{}, ErrInvalid
	}
	return s.repo.Save(ctx, o)
}

// Get returns one order.
func (s *Service) Get(ctx context.Context, id string) (Order, error) { return s.repo.Get(ctx, id) }

// List returns all orders in creation order.
func (s *Service) List(ctx context.Context) ([]Order, error) { return s.repo.List(ctx) }
