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
	for _, o := range []order.Order{{Customer: "c1", Quantity: 1}, {SKU: "A", Quantity: 1}} {
		if _, err := svc.Create(context.Background(), o); !errors.Is(err, order.ErrInvalid) {
			t.Errorf("%+v: want ErrInvalid, got %v", o, err)
		}
	}
}

func TestCreateRejectsNonPositiveQuantity(t *testing.T) {
	ctx := context.Background()
	svc := order.NewService(store.NewMemory())
	for _, q := range []int{0, -1, -3} {
		if _, err := svc.Create(ctx, order.Order{Customer: "c1", SKU: "A", Quantity: q}); !errors.Is(err, order.ErrInvalid) {
			t.Errorf("quantity %d: want ErrInvalid, got %v", q, err)
		}
	}
	list, err := svc.List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 0 {
		t.Fatalf("want no stored orders, got %+v", list)
	}
}

func TestCreateAcceptsQuantityOne(t *testing.T) {
	svc := order.NewService(store.NewMemory())
	got, err := svc.Create(context.Background(), order.Order{Customer: "c1", SKU: "A", Quantity: 1})
	if err != nil {
		t.Fatal(err)
	}
	if got.Quantity != 1 || got.ID == "" {
		t.Fatalf("got %+v", got)
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
