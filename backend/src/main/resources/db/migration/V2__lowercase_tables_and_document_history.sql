-- V1 is immutable. Preserve its checksum and all existing fact bytes/identities.
-- lower_case_table_names=1 already stores V1 names in lowercase (Windows tests).
SET @scsj_rename = IF(EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND BINARY table_name = 'SCSJ_FACT_INPUT_VERSION'), 'RENAME TABLE `SCSJ_FACT_INPUT_VERSION` TO `scsj_fact_input_version`, `SCSJ_MATERIAL_DAY_FACT` TO `scsj_material_day_fact`, `SCSJ_FACT_DAY_STATE` TO `scsj_fact_day_state`', 'DO 0');
PREPARE scsj_rename_statement FROM @scsj_rename;
EXECUTE scsj_rename_statement;
DEALLOCATE PREPARE scsj_rename_statement;

ALTER TABLE `scsj_material_day_fact`
  MODIFY `input_version_id` BIGINT UNSIGNED NOT NULL COMMENT '所属输入版本ID；关联scsj_fact_input_version.id';

ALTER TABLE `scsj_flyway_schema_history`
  COMMENT = '本系统数据库结构迁移历史；由Flyway管理，不存业务指标',
  MODIFY `installed_rank` INT NOT NULL COMMENT '迁移执行顺序主键；由Flyway分配',
  MODIFY `version` VARCHAR(50) NULL COMMENT '迁移版本号；可重复迁移为空',
  MODIFY `description` VARCHAR(200) NOT NULL COMMENT '迁移说明；由脚本名称或迁移定义提供',
  MODIFY `type` VARCHAR(20) NOT NULL COMMENT '迁移类型：SQL=SQL脚本，JDBC=Java迁移，SCRIPT=脚本迁移，UNDO_SCRIPT=撤销脚本，SCHEMA=建模式标记，BASELINE=基线，DELETE=修复删除标记，CUSTOM=自定义；本项目使用SQL，扩展类型由Flyway插件定义',
  MODIFY `script` VARCHAR(1000) NOT NULL COMMENT '迁移脚本名称或Java迁移类名',
  MODIFY `checksum` INT NULL COMMENT 'Flyway脚本校验和；不可校验的迁移类型为空，不是来源数据SHA256',
  MODIFY `installed_by` VARCHAR(100) NOT NULL COMMENT '执行迁移的数据库用户',
  MODIFY `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Flyway记录的迁移安装时间；旧记录原值保留',
  MODIFY `execution_time` INT NOT NULL COMMENT '迁移执行耗时；单位毫秒',
  MODIFY `success` BOOLEAN NOT NULL COMMENT '迁移结果枚举：1=成功，0=失败',
  ADD `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间；旧记录回填installed_on，新记录使用数据库时钟',
  ADD `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '记录修改时间；旧记录无修改历史，以installed_on初始化，新修改自动更新';

UPDATE `scsj_flyway_schema_history` SET `created_at` = `installed_on`, `updated_at` = `installed_on`;
