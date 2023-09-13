package org.ekstep.analytics.dashboard.report.assess

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, expr, lit, max}
import org.apache.spark.sql.{SaveMode, SparkSession, functions}
import org.ekstep.analytics.dashboard.{DashboardConfig, DummyInput, DummyOutput}
import org.ekstep.analytics.dashboard.DashboardUtil._
import org.ekstep.analytics.dashboard.DataUtil._
import org.ekstep.analytics.dashboard.StorageUtil._
import org.ekstep.analytics.framework.{FrameworkContext, IBatchModelTemplate, StorageConfig}

import java.io.Serializable


object UserAssessmentModel extends IBatchModelTemplate[String, DummyInput, DummyOutput, DummyOutput] with Serializable {

  implicit val className: String = "org.ekstep.analytics.dashboard.report.assess.UserAssessmentModel"
  override def name() = "UserAssessmentModel"

  override def preProcess(data: RDD[String], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyInput] = {
    // we want this call to happen only once, so that timestamp is consistent for all data points
    val executionTime = System.currentTimeMillis()
    sc.parallelize(Seq(DummyInput(executionTime)))
  }

  override def algorithm(data: RDD[DummyInput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyOutput] = {
    val timestamp = data.first().timestamp  // extract timestamp from input
    implicit val spark: SparkSession = SparkSession.builder.config(sc.getConf).getOrCreate()
    processUserAssessmentData(timestamp, config)
    sc.parallelize(Seq())  // return empty rdd
  }

  override def postProcess(data: RDD[DummyOutput], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[DummyOutput] = {
    sc.parallelize(Seq())  // return empty rdd
  }


  /**
   * Master method, does all the work, fetching, processing and dispatching
   *
   * @param timestamp unique timestamp from the start of the processing
   * @param config model config, should be defined at sunbird-data-pipeline:ansible/roles/data-products-deploy/templates/model-config.j2
   */
  def processUserAssessmentData(timestamp: Long, config: Map[String, AnyRef])(implicit spark: SparkSession, sc: SparkContext, fc: FrameworkContext): Unit = {
    // parse model config
    println(config)
    implicit val conf: DashboardConfig = parseConfig(config)
    if (conf.debug == "true") debug = true // set debug to true if explicitly specified in the config
    if (conf.validation == "true") validation = true // set validation to true if explicitly specified in the config

    val reportPathCBP = s"/tmp/standalone-reports/user-assessment-report-cbp/${getDate}/"
    val reportPathMDO = s"/tmp/standalone-reports/user-assessment-report-mdo/${getDate}/"

    // obtain user org data
    var (orgDF, userDF, userOrgDF) = getOrgUserDataFrames()

    // get course details, with rating info
    val (hierarchyDF, allCourseProgramDetailsWithCompDF, allCourseProgramDetailsDF,
      allCourseProgramDetailsWithRatingDF) = contentDataFrames(orgDF)

    val assessmentDF = assessmentESDataFrame()
    val assessmentWithOrgDF = assessWithOrgDataFrame(assessmentDF, orgDF)
    val assessWithHierarchyDF = assessWithHierarchyDataFrame(assessmentWithOrgDF, hierarchyDF)
    val assessWithDetailsDF = assessWithHierarchyDF.drop("children")
    // kafka dispatch to dashboard.assessment
    kafkaDispatch(withTimestamp(assessWithDetailsDF, timestamp), conf.assessmentTopic)

    val assessChildrenDF = assessmentChildrenDataFrame(assessWithHierarchyDF)
    val userAssessmentDF = userAssessmentDataFrame()
    val userAssessChildrenDF = userAssessmentChildrenDataFrame(userAssessmentDF, assessChildrenDF)
    val userAssessChildrenDetailsDF = userAssessmentChildrenDetailsDataFrame(userAssessChildrenDF, assessWithDetailsDF,
      allCourseProgramDetailsWithRatingDF, userOrgDF)
    // kafka dispatch to dashboard.user.assessment
    kafkaDispatch(withTimestamp(userAssessChildrenDetailsDF, timestamp), conf.userAssessmentTopic)

    var df = userAssessChildrenDetailsDF

    // get the mdoids for which the report are requesting
    val mdoID = conf.mdoIDs
    val mdoIDDF = mdoIDsDF(mdoID)

    val mdoData = mdoIDDF.join(orgDF, Seq("orgID"), "inner").select(col("orgID").alias("assessOrgID"), col("orgName"))

    df = df.join(mdoData, Seq("assessOrgID"), "inner")
    df = df.withColumn("fullName", functions.concat(col("firstName"), lit(' '), col("lastName")))

    val latest = df.groupBy(col("assessChildID"), col("userID")).agg(max("assessEndTimestamp").alias("assessEndTimestamp"))
    latest.show()

    df = df.join(latest, Seq("assessChildID", "userID", "assessEndTimestamp"), "inner")

    val caseExpression = "CASE WHEN assessPass == 1 AND assessUserStatus == 'SUBMITTED' THEN 'Pass' WHEN assessPass == 0 AND assessUserStatus == 'SUBMITTED' THEN 'Fail' " +
      " ELSE 'N/A' END"
    df = df.withColumn("Assessment_Status", expr(caseExpression))

    val caseExpressionCompletionStatus = "CASE WHEN assessUserStatus == 'SUBMITTED' THEN 'Completed' ELSE 'In progress' END"
    df = df.withColumn("Overall_Status", expr(caseExpressionCompletionStatus))

    df = df.dropDuplicates("userID").select(
      col("userID").alias("User_id"),
      col("assessName").alias("Assessment_Name"),
      col("Overall_Status"),
      col("Assessment_Status"),
      col("assessPassPercentage").alias("Percentage_Of_Score"),
      col("maskedEmail").alias("Email"),
      col("maskedPhone").alias("Phone"),
      col("assessOrgID").alias("mdoid")
    )

    import spark.implicits._
    val storageService = getStorageService(conf)

    val cbpids = df.select("mdoid").map(row => row.getString(0)).collect().toArray

    df.repartition(1).write.mode(SaveMode.Overwrite).format("csv").option("header", "true").partitionBy("mdoid")
      .save(reportPathCBP)

    removeFile(reportPathCBP + "_SUCCESS")
    renameCSV(cbpids, reportPathCBP)

    // upload cbp files - s3://{container}/standalone-reports/user-assessment-report-cbp/{date}/mdoid={mdoid}/{mdoid}.csv
    val storageConfigCbp = new StorageConfig(conf.store, conf.container, reportPathCBP)
    storageService.upload(storageConfigCbp.container, reportPathCBP,
      s"standalone-reports/user-assessment-report-cbp/${getDate}/", Some(true), Some(0), Some(3), None)

    closeRedisConnect()

  }

}