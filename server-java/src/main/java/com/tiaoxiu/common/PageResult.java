package com.tiaoxiu.common;

import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 统一分页返回结构。
 *
 * <p>用于所有「分页列表查询」接口的响应体。相比 {@link Result} 额外携带
 * 总条数、当前页码、每页大小与总页数，前端分页组件可直接消费。
 *
 * <p>约定 {@code code = 0} 表示成功；{@code page} 为 0 基页码（与 Spring Data 的 Page.getNumber() 一致）。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    /** 响应码，0 表示成功 */
    private int code = 0;

    /** 响应提示信息 */
    private String message = "ok";

    /** 当前页数据列表 */
    private List<T> data;

    /** 符合条件的总记录数（不是当前页条数） */
    private long total;

    /** 当前页码，从 0 开始 */
    private int page;

    /** 每页大小 */
    private int size;

    /** 总页数 */
    private int totalPages;

    /**
     * 把 Spring Data 的 {@link Page} 转换为本分页结构。
     *
     * @param page Spring Data 分页查询结果（内含内容、总条数、页码、页大小、总页数）
     * @param <T>  列表元素类型
     * @return 组装好的分页响应对象
     */
    public static <T> PageResult<T> of(Page<T> page) {
        PageResult<T> r = new PageResult<>();
        r.data = page.getContent();
        r.total = page.getTotalElements();
        r.page = page.getNumber();
        r.size = page.getSize();
        r.totalPages = page.getTotalPages();
        return r;
    }
}
