package com.ims.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
// Every read of an order loads its items by order_id (and the FK alone does not create an index
// in Postgres), so without this the item lookup is a sequential scan of the whole table.
@Table(name = "order_items", indexes = @Index(name = "idx_order_items_order_id", columnList = "order_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /** Whether inventory-service actually decremented stock for this line item; drives compensation on partial failure. */
    @Column(name = "reserved", nullable = false)
    private boolean reserved;
}
