package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageMetadataBuscaLocal implements Serializable {
    private int number;
    private int size;
    private long totalElements;
    private int totalPages;
}
