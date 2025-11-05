package handlers

import (
	"inventory-service/internal/service"
	"net/http"

	"github.com/gin-gonic/gin"
)

type InventoryHandler struct {
    service service.InventoryService
}

func NewInventoryHandler(service service.InventoryService) *InventoryHandler {
    return &InventoryHandler{service: service}
}

func (h *InventoryHandler) GetInventory(c *gin.Context) {
    productID := c.Param("productId")
    
    inventory, err := h.service.GetInventory(productID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    
    if inventory == nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "Inventory not found"})
        return
    }
    
    c.JSON(http.StatusOK, inventory)
}

func (h *InventoryHandler) UpdateInventory(c *gin.Context) {
    var request struct {
        ProductID string `json:"product_id" binding:"required"`
        Quantity  int    `json:"quantity" binding:"required"`
    }
    
    if err := c.ShouldBindJSON(&request); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    
    err := h.service.UpdateInventory(request.ProductID, request.Quantity)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    
    c.JSON(http.StatusOK, gin.H{"message": "Inventory updated successfully"})
}