package models

type ProductCreatedEvent struct {
	ProductID    string  `json:"productId"`
	ProductName  string  `json:"productName"`
	SellerID     string  `json:"sellerId"`
	Price        float64 `json:"price"`
	InitialStock int     `json:"initialStock"`
	CreatedAt    string  `json:"createdAt"`
}

type ProductUpdatedEvent struct {
	ProductID   string  `json:"productId"`
	ProductName string  `json:"productName"`
	Price       float64 `json:"price"`
	Active      bool    `json:"active"`
	UpdatedAt   string  `json:"updatedAt"`
}

type ProductDeletedEvent struct {
	ProductID string `json:"productId"`
	DeletedAt string `json:"deletedAt"`
}
