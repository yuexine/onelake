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
@Table(name = "data_model_source", schema = "modeling")
@Getter
@Setter
public class DataModelSource {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID modelId;

    @Column(nullable = false)
    private String sourceFqn;

    @Column(nullable = false, length = 32)
    private String sourceType = "ODS_TABLE";

    private Integer sortNo = 0;
}
