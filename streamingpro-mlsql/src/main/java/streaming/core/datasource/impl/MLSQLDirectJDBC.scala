package streaming.core.datasource.impl

import net.sf.json.JSONObject
import org.apache.spark.sql.catalyst.plans.logical.MLSQLDFParser
import org.apache.spark.sql.execution.MLSQLAuthParser
import org.apache.spark.sql.mlsql.session.{MLSQLException, MLSQLSparkSession}
import org.apache.spark.sql.{DataFrame, DataFrameReader, DataFrameWriter, Row}
import streaming.core.datasource._
import streaming.dsl.auth.{MLSQLTable, OperateType, TableType}
import streaming.dsl.{ConnectMeta, DBMappingKey, ScriptSQLExec}
import streaming.log.{Logging, WowLog}
import tech.mlsql.Stage
import tech.mlsql.dsl.auth.DatasourceAuth
import tech.mlsql.sql.MLSQLSparkConf

import scala.collection.JavaConverters._

/**
  * 2018-12-21 WilliamZhu(allwefantasy@gmail.com)
  */
class MLSQLDirectJDBC extends MLSQLDirectSource with MLSQLDirectSink with MLSQLSourceInfo with MLSQLRegistry
  with DatasourceAuth with Logging with WowLog {

  override def fullFormat: String = "jdbc"

  override def shortFormat: String = fullFormat

  override def dbSplitter: String = "."

  def toSplit = "\\."

  private def loadConfigFromExternal(params: Map[String, String], path: String) = {
    var _params = params
    var isRealDB = true
    if (path.contains(".")) {
      val Array(db, table) = path.split("\\.", 2)
      ConnectMeta.presentThenCall(DBMappingKey("jdbc", db), options => {
        options.foreach { item =>
          _params += (item._1 -> item._2)
        }
        isRealDB = false
      })
    }
    _params
  }


  override def load(reader: DataFrameReader, config: DataSourceConfig): DataFrame = {
    var _params = config.config
    val path = config.path
    if (path.contains(".")) {
      val Array(db, table) = path.split("\\.", 2)
      ConnectMeta.presentThenCall(DBMappingKey("jdbc", db), options => {
        options.foreach { item =>
          _params += (item._1 -> item._2)
        }
      })
    }
    val res = JDBCUtils.executeQueryInDriver(_params ++ Map("driver-statement-query" -> config.config("directQuery")))
    val spark = config.df.get.sparkSession
    val rdd = spark.sparkContext.parallelize(res.map(item => JSONObject.fromObject(item.asJava).toString()))
    spark.read.json(rdd)
  }

  override def save(writer: DataFrameWriter[Row], config: DataSinkConfig): Unit = {
    throw new MLSQLException("not support yet....")
  }

  override def register(): Unit = {
    DataSourceRegistry.register(MLSQLDataSourceKey(fullFormat, MLSQLDirectDataSourceType), this)
    DataSourceRegistry.register(MLSQLDataSourceKey(shortFormat, MLSQLDirectDataSourceType), this)
  }

  def sourceInfo(config: DataAuthConfig): SourceInfo = {
    val Array(_dbname, _dbtable) = if (config.path.contains(dbSplitter)) {
      config.path.split(toSplit, 2)
    } else {
      Array("", config.path)
    }

    val url = if (config.config.contains("url")) {
      config.config.get("url").get
    } else {
      val format = config.config.getOrElse("implClass", fullFormat)

      ConnectMeta.options(DBMappingKey(format, _dbname)) match {
        case Some(item) => item("url")
        case None => throw new RuntimeException(
          s"""
             |format: ${format}
             |ref:${_dbname}
             |However ref is not found,
             |Have you set the connect statement properly?
           """.stripMargin)
      }
    }

    val dataSourceType = url.split(":")(1)
    val dbName = url.substring(url.lastIndexOf('/') + 1).takeWhile(_ != '?')
    val si = SourceInfo(dataSourceType, dbName, _dbtable)
    SourceTypeRegistry.register(dataSourceType, si)

    si
  }

  override def auth(path: String, params: Map[String, String]): List[MLSQLTable] = {
    val si = this.sourceInfo(DataAuthConfig(path, params))

    val context = ScriptSQLExec.contextGetOrForTest()

    // we should auth all tables in direct query
    val sql = params("directQuery")
    // first, only select supports
    if (!sql.trim.toLowerCase.startsWith("select ")) {
      throw new MLSQLException("JDBC direct query only support select statement")
    }
    logInfo("Auth direct query tables.... ")
    //second, we should extract all tables from the sql

    val tableRefs = MLSQLAuthParser.filterTables(sql, context.execListener.sparkSession)

    def isSkipAuth = {
      context.execListener.env()
        .getOrElse("SKIP_AUTH", "true")
        .toBoolean
    }

    tableRefs.foreach { tableIdentify =>
      if (tableIdentify.database.isDefined) {
        throw new MLSQLException("JDBC direct query should not allow using db prefix. Please just use table")
      }
    }
    if (!isSkipAuth && context.execListener.getStage.get == Stage.auth && !MLSQLSparkConf.runtimeDirectQueryAuth) {

      // generate all tables
      tableRefs.map { tableIdentify =>
        MLSQLTable(Some(tableIdentify.database.getOrElse(si.db)), Some(tableIdentify.table),
          OperateType.DIRECT_QUERY,
          Some(si.sourceType), TableType.JDBC)
      }.foreach { mlsqlTable =>
        context.execListener.authProcessListner match {
          case Some(authProcessListener) =>
            authProcessListener.addTable(mlsqlTable)
          case None =>
        }
      }
    }

    if (!isSkipAuth && context.execListener.getStage.get == Stage.physical && MLSQLSparkConf.runtimeDirectQueryAuth) {
      val _params = loadConfigFromExternal(params, path)
      //clone new sparksession so we will not affect current spark context catalog
      val spark = MLSQLSparkSession.cloneSession(context.execListener.sparkSession)
      tableRefs.map { tableIdentify =>
        spark.read.options(_params + ("dbtable" -> tableIdentify.table)).format("jdbc").load().createOrReplaceTempView(tableIdentify.table)
      }

      val df = spark.sql(sql)
      val tableAndCols = MLSQLDFParser.extractTableWithColumns(df)
      var mlsqlTables = List.empty[MLSQLTable]

      tableAndCols.foreach {
        case (table, cols) =>
          mlsqlTables ::= MLSQLTable(Option(si.db), Option(table), Option(cols.toSet), OperateType.DIRECT_QUERY, Option(si.sourceType), TableType.JDBC)
      }
      context.execListener.getTableAuth.foreach { tableAuth =>
        println(mlsqlTables)
        tableAuth.auth(mlsqlTables)
      }

    }
    List()
  }
}
