package models

type ProductCreatedEvent struct {
	ProductID    string  `json:"productId"`
	ProductName  string  `json:"productName"`
	SellerID     string  `json:"sellerId"`
	Price        float64 `json:"price"`
	InitialStock int     `json:"initialStock"`
	CreatedAt    string  `json:"createdAt"`
}