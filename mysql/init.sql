SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

DROP TABLE IF EXISTS china_regions;
CREATE TABLE china_regions (
  id int NOT NULL AUTO_INCREMENT,
  level tinyint NOT NULL COMMENT '1=province,2=city,3=county',
  name varchar(64) NOT NULL,
  code varchar(12) NOT NULL,
  parent_code varchar(12) DEFAULT NULL,
  province_code varchar(12) DEFAULT NULL,
  city_code varchar(12) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uniq_china_region_code (code),
  KEY idx_china_region_level_name (level, name),
  KEY idx_china_region_parent (parent_code),
  KEY idx_china_region_province_city (province_code, city_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS=1;
