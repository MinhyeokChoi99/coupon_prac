-- Local monitoring account, separate from the application's connection pool.
CREATE USER 'exporter'@'%' IDENTIFIED BY 'coupon-exporter' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
