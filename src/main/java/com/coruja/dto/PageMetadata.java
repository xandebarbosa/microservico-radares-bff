package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
//@JsonIgnoreProperties(ignoreUnknown = true)
public class PageMetadata {
    private int size;
    private int number;
    private long totalElements;
    private int totalPages;

    public PageMetadata(int number, int size, long totalElements, int totalPages) {
        this.number = number;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }
}
