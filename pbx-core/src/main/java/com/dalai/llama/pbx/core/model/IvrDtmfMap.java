package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ivr_dtmf_map")
public class IvrDtmfMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dtmf_key", nullable = false)
    private String dtmfKey;                        // 1, 2, 3, *

    @Column(name = "next_node", nullable = false)
    private String nextNode;                       // child node to jump to

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id_fk", nullable = false)
    private IvrNode node;                    // parent IVR node
}
