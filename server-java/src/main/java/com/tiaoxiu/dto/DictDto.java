package com.tiaoxiu.dto;

import lombok.Data;

/**
 * 数据字典相关的出入参定义。
 */
public class DictDto {

    /** 新增字典类型的请求参数 */
    @Data
    public static class TypeCreateRequest {
        /** 字典类型编码，全局唯一 */
        private String code;
        /** 字典类型名称 */
        private String name;
    }

    /** 新增或修改字典项的请求参数（有 id 则更新、无则新增，实体层按 typeCode 归属） */
    @Data
    public static class DataUpsertRequest {
        /** 所属字典类型编码 */
        private String typeCode;
        /** 选项值，实际存库与业务判断用 */
        private String value;
        /** 选项显示文案 */
        private String label;
        /** 排序号，越小越靠前 */
        private Integer sort;
        /** 状态：ACTIVE 正常 / INACTIVE 停用 */
        private String status;    // ACTIVE / INACTIVE
    }
}
