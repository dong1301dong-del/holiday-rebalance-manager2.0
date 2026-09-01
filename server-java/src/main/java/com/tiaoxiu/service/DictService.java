package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.DictDto;
import com.tiaoxiu.entity.DictData;
import com.tiaoxiu.entity.DictType;
import com.tiaoxiu.repository.DictDataRepository;
import com.tiaoxiu.repository.DictTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 数据字典：字典类型 + 字典数据。 */
@Service
public class DictService {

    private final DictTypeRepository typeRepository;
    private final DictDataRepository dataRepository;

    /**
     * 构造器注入。
     *
     * @param typeRepository 字典类型仓储
     * @param dataRepository 字典项仓储
     */
    public DictService(DictTypeRepository typeRepository, DictDataRepository dataRepository) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
    }

    /**
     * 查询全部字典类型。
     *
     * @return 字典类型列表
     */
    public List<DictType> listTypes() {
        return typeRepository.findAll();
    }

    /**
     * 新增字典类型。
     *
     * @param req 新增请求
     * @return 保存后的字典类型
     * @throws BizException 字典类型编码已存在时抛出
     */
    @Transactional
    public DictType createType(DictDto.TypeCreateRequest req) {
        if (typeRepository.existsByCode(req.getCode())) throw new BizException("字典类型编码已存在");
        DictType t = new DictType();
        t.setCode(req.getCode());
        t.setName(req.getName());
        return typeRepository.save(t);
    }

    /**
     * 查询某字典类型下的全部选项（不限状态），按排序号升序。
     *
     * @param typeCode 字典类型编码
     * @return 字典项列表
     */
    public List<DictData> listData(String typeCode) {
        return dataRepository.findByTypeCodeOrderBySortAsc(typeCode);
    }

    /**
     * 新增字典项（归属的字典类型必须已存在）。
     *
     * <p>说明：当前实现为「总是新增」，不做按 value 更新；如需修改请先删除旧项。
     *
     * @param req 新增请求
     * @return 保存后的字典项
     * @throws BizException 所属字典类型不存在时抛出
     */
    @Transactional
    public DictData upsertData(DictDto.DataUpsertRequest req) {
        if (!typeRepository.existsByCode(req.getTypeCode())) throw new BizException("字典类型不存在");
        DictData d = new DictData();
        d.setTypeCode(req.getTypeCode());
        d.setValue(req.getValue());
        d.setLabel(req.getLabel());
        d.setSort(req.getSort() == null ? 0 : req.getSort());
        d.setStatus(StringUtils.hasText(req.getStatus()) ? req.getStatus() : "ACTIVE");
        return dataRepository.save(d);
    }

    /**
     * 删除字典项。
     *
     * @param id 字典项 ID
     */
    @Transactional
    public void deleteData(Long id) {
        dataRepository.deleteById(id);
    }
}
