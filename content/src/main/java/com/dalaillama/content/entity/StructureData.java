package com.dalaillama.content.entity;

//import javax.persistence.*;
import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Data
@Entity
@Table(name="structure_data")
public class StructureData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="structure_id")
    private int structureId;

    @Column(name="user_id")
    private int userId;

    @Column(name="generated_structure")
    private String structure;

    @Column(name="selected_structure")
    private String selectedStructure;
}
