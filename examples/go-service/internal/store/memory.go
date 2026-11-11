// Package store provides order repositories.
package store

import (
	"context"
	"fmt"
	"sync"

	"example.com/go-service/internal/order"
)

// Memory is an in-memory, concurrency-safe order repository.
type Memory struct {
	mu     sync.RWMutex
	orders []order.Order
	byID   map[string]int
}

// NewMemory returns an empty repository.
func NewMemory() *Memory { return &Memory{byID: map[string]int{}} }

// Save assigns an ID and stores o.
func (m *Memory) Save(_ context.Context, o order.Order) (order.Order, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	o.ID = fmt.Sprintf("ord-%d", len(m.orders)+1)
	m.byID[o.ID] = len(m.orders)
	m.orders = append(m.orders, o)
	return o, nil
}

// Get returns the order with id.
func (m *Memory) Get(_ context.Context, id string) (order.Order, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	i, ok := m.byID[id]
	if !ok {
		return order.Order{}, order.ErrNotFound
	}
	return m.orders[i], nil
}

// List returns a copy of all orders.
func (m *Memory) List(_ context.Context) ([]order.Order, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return append([]order.Order(nil), m.orders...), nil
}
