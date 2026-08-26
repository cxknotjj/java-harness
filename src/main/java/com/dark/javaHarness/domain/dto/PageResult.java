package com.dark.javaHarness.domain.dto;

import java.util.List;

/**
 * 通用分页结果。
 *
 * @param list  当前页数据
 * @param total 总记录数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 */
public record PageResult<T>(List<T> list, long total, long page, long size) {
}