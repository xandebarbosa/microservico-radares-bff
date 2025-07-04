package com.coruja.dto;

import lombok.Data;

import java.util.List;

@Data
public class RadarPageResponse {
    private List<RadarDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public RadarPageResponse(List<RadarDTO> content, int page, int size, long totalElements, int totalPages) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

}
