package com.onelake.modeling.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "subject_domain", schema = "modeling")
@Getter @Setter
public class SubjectDomain {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    private UUID parentId;
}
