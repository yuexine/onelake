package com.onelake.modeling.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "data_model_column_mapping", schema = "modeling")
@Getter
@Setter
public class DataModelColumnMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID modelId;

    @Column(nullable = false)
    private String sourceColumn;

    @Column(nullable = false)
    private String targetColumn;

    private String sourceType;
    private String targetType;

    @Column(columnDefinition = "text")
    private String expression;

    @Column(name = "primary_key", nullable = false)
    private Boolean primaryKey = false;

    private String classification;
    private String piiType;
    private String suggestLevel;
    private UUID termId;
    private String termCode;
    private String termName;
    private Integer sortNo = 0;
}
