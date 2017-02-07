@Grab('org.influxdb:influxdb-java:2.5')
@Grab('com.oracle:ojdbc14:10.2.0.5')

import com.google.common.collect.ArrayListMultimap
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.BatchPoints
import org.influxdb.dto.Point
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

import javax.annotation.PostConstruct
import java.sql.ResultSet
import java.sql.SQLException

@EnableScheduling
class JmsInfluxDBConfiguration {
    final Logger logger = LoggerFactory.getLogger(JmsInfluxDBConfiguration.class);

    final String JMS_QUEUES_TABLES_FORMAT = "SELECT OWNER, OBJECT_NAME FROM DBA_OBJECTS  WHERE OBJECT_TYPE='TABLE' AND OBJECT_NAME like :name AND OWNER = :owner";
    final String JMS_CHECK_SQL_FORMAT = "SELECT COUNT(1) FROM %s WHERE RETRY_COUNT < 5"

    String dbName = "jms"
    String url = "http://192.168.8.16:32249"
    String user = "apm"
    String password = "apm"

    org.influxdb.InfluxDB influxDB

    @Value('${ips.apm.jms.schemas}')
    String[] schemas

    @Autowired
    JdbcTemplate jdbcTemplate


    @PostConstruct
    void init() {
        influxDB = InfluxDBFactory.connect(url, user, password);
        logger.info("### connect influxdb({},{}) success!", url, user)
        List<String> dbs = influxDB.describeDatabases();
        if (!dbs.contains(dbName)) {
            influxDB.createDatabase(dbName);
        }
        influxDB.enableGzip();

        logger.info("### jdbcTemplate:{}", jdbcTemplate.toString())
        logger.info("### schemas:{}", schemas)

    }

    /**
     * 30秒检查一次
     */
    @Scheduled(fixedDelay = 30000L)
    void point() {
        BatchPoints batchPoints = BatchPoints
                .database(dbName)
                .tag("source", "jms")
                .consistency(InfluxDB.ConsistencyLevel.ANY)
                .build();

        ArrayListMultimap<String, String> schemaQueueNameMap = getSchemaQueueNameMap();
        for (String schema : schemaQueueNameMap.keySet()) {
            points(batchPoints, schema, schemaQueueNameMap.get(schema));
        }

        if (!batchPoints.points.isEmpty()){
            influxDB.write(batchPoints)
            logger.info("### write to influxdb success!")
        }

    }

    ArrayListMultimap<String, String> getSchemaQueueNameMap() {
        ArrayListMultimap result = ArrayListMultimap.create();
        for (String schema : schemas) {
            logger.info("query schema({})", schema)
            List<String> queueNames = jdbcTemplate.query(JMS_QUEUES_TABLES_FORMAT, new RowMapper<String>() {

                @Override
                String mapRow(ResultSet resultSet, int i) throws SQLException {
                    logger.info("queue:({}.{})", resultSet.getString("OWNER"), resultSet.getString("OBJECT_NAME"))
                    return resultSet.getString("OWNER") + "." + resultSet.getString("OBJECT_NAME");
                }
            }, "%JMS_%", schema);

            if (queueNames.size() > 0) {
                result.putAll(schema, queueNames);
            }
        }
        return result;
    }

    BatchPoints points(BatchPoints batchPoints, String schema, List<String> queueNames) {
        for (String queue : queueNames) {
            batchPoints.point(pointQueue(schema, queue));
        }
        return batchPoints;

    }

    Point pointQueue(String schema, String queue) {
        long count = this.jdbcTemplate.query(String.format(JMS_CHECK_SQL_FORMAT, queue), new ResultSetExtractor<Long>() {
            @Override
            Long extractData(ResultSet rs) throws SQLException, DataAccessException {
                if (rs.next())
                    return rs.getLong(1);
                else
                    return 0L;
            }
        });
        logger.info("point queue(length:{},schema:{},name:{})", count, schema, queue)
        return Point.measurement("queue")
                .addField("length", count)
                .tag("schema", schema)
                .tag("name", queue)
                .build();
    }
}