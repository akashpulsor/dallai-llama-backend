package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ivr_node",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "node_id"}))
public class IvrNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;                         // e.g. main, sales, support

    @Column(name = "prompt")
    private String prompt;                         // audio file path

    @Column(name = "bot_enabled")
    private boolean botEnabled;

    @Column(name = "parent_node_id")
    private String parentNodeId;                   // null if root

    @Column(name = "transfer_number")
    private String transferNumber;                 // optional PSTN redirect

    @Column(name = "is_final_node")
    private boolean finalNode;

    // -----------------------------
    // DTMF Mappings (1 → sales)
    // -----------------------------
    @OneToMany(mappedBy = "node",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<IvrDtmfMap> dtmfMappings = new ArrayList<>();

    // utility method
    public void addDtmf(String key, String nextNode) {
        IvrDtmfMap m = new IvrDtmfMap();
        m.setDtmfKey(key);
        m.setNextNode(nextNode);
        m.setNode(this);
        dtmfMappings.add(m);
    }
}
