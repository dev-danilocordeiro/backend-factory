package order_test

import (
	"context"
	"errors"
	"testing"

	"example.com/go-service/internal/order"
	"example.com/go-service/internal/store"
)

func TestCreateRejectsMissingFields(t *testing.T) {
	svc := order.NewService(store.NewMemory())
	for _, o := range []order.Order{{Customer: "c1"}, {SKU: "A"}} {
		if _, err := svc.Create(context.Background(), o); !errors.Is(err, order.ErrInvalid) {
			t.Errorf("%+v: want ErrInvalid, got %v", o, err)
		}
	}
}

func TestCreateAndGet(t *testing.T) {
	svc := order.NewService(store.NewMemory())
	created, err := svc.Create(context.Background(), order.Order{Customer: "c1", SKU: "A", Quantity: 2})
	if err != nil {
		t.Fatal(err)
	}
	got, err := svc.Get(context.Background(), created.ID)
	if err != nil || got != created {
		t.Fatalf("got %+v, %v", got, err)
	}
}
