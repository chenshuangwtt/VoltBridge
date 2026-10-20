package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code billing_line_item} 表的账单明细行映射。 */
@TableName("billing_line_item")
public class BillingLineItemEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public UUID billingRecordId; public Integer lineNo; public String lineType; public String description;
    public BigDecimal quantity; public String unit; public BigDecimal unitPrice; public BigDecimal amount;
}
