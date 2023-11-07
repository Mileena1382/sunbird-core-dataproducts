package org.ekstep.analytics.model

import java.io.Serializable

import org.ekstep.analytics.framework.IBatchModelTemplate
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.mutable.Buffer
import org.apache.spark.HashPartitioner
import org.ekstep.analytics.framework.JobContext
import org.apache.commons.lang3.StringUtils
import org.ekstep.analytics.framework.Level.INFO
import org.ekstep.analytics.framework.util.JSONUtils
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.util.Constants
import org.ekstep.analytics.framework.util.JobLogger
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework._


case class WorkflowInput2(sessionKey: WorkflowIndex2, events: Buffer[String]) extends AlgoInput
case class WorkflowIndex2(actroId: String, did: String, channel: String, pdataId: String)
case class WorkFlowIndexEvent2(actor: Actor, eid: String, context: V3Context)
case class Actor(id: _root_.scala.Predef.String)


object WorkFlowSummaryModel2 extends IBatchModelTemplate[String, WorkflowInput2, MeasuredEvent, MeasuredEvent] with Serializable {

    implicit val className = "org.ekstep.analytics.model.WorkFlowSummaryModel2"
    override def name: String = "WorkFlowSummaryModel2"
    val serverEvents = Array("LOG", "AUDIT", "SEARCH");

    override def preProcess(data: RDD[String], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[WorkflowInput2] = {

        val defaultPDataId = V3PData(AppConf.getConfig("default.consumption.app.id"), Option("1.0"))
        val parallelization = config.getOrElse("parallelization", 20).asInstanceOf[Int];
        val indexedData = data.map{f =>
                try {
                    (JSONUtils.deserialize[WorkFlowIndexEvent2](f), f)
                }
                catch {
                    case ex: Exception =>
                        JobLogger.log(ex.getMessage, None, INFO)
                        (null.asInstanceOf[WorkFlowIndexEvent2], "")
                }
        }.filter(f => null != f._1)

      val partitionedData = indexedData
        .filter(f => null != f._1.eid && !serverEvents.contains(f._1.eid))
        .map { x => (
            WorkflowIndex2(x._1.actor.id, x._1.context.did.getOrElse(""), x._1.context.channel, x._1.context.pdata.getOrElse(defaultPDataId).id),
            Buffer(x._2)
          )
        }
        .partitionBy(new HashPartitioner(parallelization))
        .reduceByKey((a, b) => a ++ b);

        partitionedData.map { x => WorkflowInput2(x._1, x._2) }
    }
    
    override def algorithm(data: RDD[WorkflowInput2], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[MeasuredEvent] = {
        
        
        val idleTime = config.getOrElse("idleTime", 600).asInstanceOf[Int];
        val sessionBreakTime = config.getOrElse("sessionBreakTime", 30).asInstanceOf[Int];

        val outputEventsCount = fc.outputEventsCount;

        data.map({ f =>
            var summEvents: Buffer[MeasuredEvent] = Buffer();

            val events = f.events.map{f =>
                try {
                    JSONUtils.deserialize[WFSInputEvent](f)
                } catch {
                    case ex: Exception =>
                        JobLogger.log(ex.getMessage, None, INFO)
                        null.asInstanceOf[WFSInputEvent]
                }
            }.filter(f => null != f)

            val sortedEvents = events.sortBy { x => x.ets }

            var rootSummary: org.ekstep.analytics.util.Summary2 = null
            var currSummary: org.ekstep.analytics.util.Summary2 = null
            var prevEvent: WFSInputEvent = sortedEvents.head
            
            sortedEvents.foreach{ x =>

                val diff = CommonUtil.getTimeDiff(prevEvent.ets, x.ets).get
                if(diff > (sessionBreakTime * 60) && !StringUtils.equalsIgnoreCase("app", x.edata.`type`)) {
                    if(currSummary != null && !currSummary.isClosed){
                        val clonedRootSummary = currSummary.deepClone()
                        clonedRootSummary.close(summEvents, config)
                        summEvents ++= clonedRootSummary.summaryEvents
                        clonedRootSummary.clearAll()
                        rootSummary = clonedRootSummary
                        currSummary.clearSummary()
                    }
                    else {}
                }
                prevEvent = x
                (x.eid) match {

                    case ("START") =>
                        if (rootSummary == null || rootSummary.isClosed) {
                            if ((StringUtils.equalsIgnoreCase("START", x.eid) && !StringUtils.equalsIgnoreCase("app", x.edata.`type`))) {
                                rootSummary = new org.ekstep.analytics.util.Summary2(x)
                                rootSummary.updateType("app")
                                rootSummary.resetMode()
                                currSummary = new org.ekstep.analytics.util.Summary2(x)
                                rootSummary.addChild(currSummary)
                                currSummary.addParent(rootSummary, idleTime)
                            }
                            else {
//                                if(currSummary != null && !currSummary.isClosed){
//                                    println("Inside first missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
//                                    currSummary.close(summEvents, config);
//                                    summEvents ++= currSummary.summaryEvents;
//                                }
                                rootSummary = new org.ekstep.analytics.util.Summary2(x)
                                currSummary = rootSummary
                            } 
                        }
//                        else if (currSummary == null || currSummary.isClosed) {
//                            println("Inside second missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
//                            currSummary = new org.ekstep.analytics.util.Summary2(x)
//                            if (!currSummary.checkSimilarity(rootSummary)) rootSummary.addChild(currSummary)
//                        }
                        else {
                            val tempSummary = currSummary.checkStart(x.edata.`type`, Option(x.edata.mode), currSummary.summaryEvents, config)
                            if (tempSummary == null) {
                                val newSumm = new org.ekstep.analytics.util.Summary2(x)
                                if (!currSummary.isClosed) {
                                    currSummary.addChild(newSumm)
                                    newSumm.addParent(currSummary, idleTime)
                                }
                                currSummary = newSumm
                            }
                            else {
                                if(tempSummary.PARENT != null && tempSummary.isClosed) {
                                     summEvents ++= tempSummary.summaryEvents
                                     val newSumm = new org.ekstep.analytics.util.Summary2(x)
//                                     if (!currSummary.isClosed) {
//                                         println("Inside 3rd missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode)
//                                        JobLogger.log("Inside 3rd missing code: " + currSummary.isClosed + " " + currSummary.sid + " " + currSummary.`type` + " " + currSummary.mode, None, INFO)
//                                        currSummary.addChild(newSumm)
//                                        newSumm.addParent(currSummary, idleTime)
//                                     }
                                     currSummary = newSumm
                                     tempSummary.PARENT.addChild(currSummary)
                                     currSummary.addParent(tempSummary.PARENT, idleTime)
                                }
                                else {
                                  if (currSummary.PARENT != null) {
                                    summEvents ++= currSummary.PARENT.summaryEvents
                                  }
                                  else {
                                    summEvents ++= currSummary.summaryEvents
                                  }
                                  currSummary = new org.ekstep.analytics.util.Summary2(x)
                                  if(rootSummary.isClosed) {
                                    summEvents ++= rootSummary.summaryEvents
                                    rootSummary = currSummary
                                  }
                              }
                            }
                        }
                    case ("END") =>
                        // Check if first event is END event, currSummary = null
                        if(currSummary != null && (currSummary.checkForSimilarSTART(x.edata.`type`, if(x.edata.mode == null) "" else x.edata.mode))) {
                            val parentSummary = currSummary.checkEnd(x, idleTime, config)
                            if(currSummary.PARENT != null && parentSummary.checkSimilarity(currSummary.PARENT)) {
                                if (!currSummary.isClosed) {
                                    currSummary.add(x, idleTime)
                                    currSummary.close(summEvents, config);
                                    summEvents ++= currSummary.summaryEvents
                                    currSummary = parentSummary
                                }
                            }
                            else if(parentSummary.checkSimilarity(rootSummary)) {
                                val similarEndSummary = currSummary.getSimilarEndSummary(x)
                                if(similarEndSummary.checkSimilarity(rootSummary)) {
                                    rootSummary.add(x, idleTime)
                                    rootSummary.close(rootSummary.summaryEvents, config)
                                    summEvents ++= rootSummary.summaryEvents
                                    currSummary = rootSummary
                                }
                                else {
                                    if (!similarEndSummary.isClosed) {
                                        similarEndSummary.add(x, idleTime)
                                        similarEndSummary.close(summEvents, config);
                                        summEvents ++= similarEndSummary.summaryEvents
                                        currSummary = parentSummary
                                    }
                                }
                            }
                            else {
                              if (!currSummary.isClosed) {
                                currSummary.add(x, idleTime)
                                currSummary.close(summEvents, config);
                                summEvents ++= currSummary.summaryEvents
                              }
                              currSummary = parentSummary
                            }
                        }
                        else {}
                    case _ =>
                        if (currSummary != null && !currSummary.isClosed) {
                            currSummary.add(x, idleTime)
                        }
                        else{
                            currSummary = new org.ekstep.analytics.util.Summary2(x)
                            currSummary.updateType("app")
                            if(rootSummary == null || rootSummary.isClosed)
                                rootSummary = currSummary
                        }
                }
            }

            if(currSummary != null && !currSummary.isClosed){
                currSummary.close(currSummary.summaryEvents, config)
                summEvents ++= currSummary.summaryEvents
                if(rootSummary != null && !currSummary.checkSimilarity(rootSummary) && !rootSummary.isClosed){
                    rootSummary.close(rootSummary.summaryEvents, config)
                    summEvents ++= rootSummary.summaryEvents
                }
            }
            else {}
            val out = summEvents.distinct;
            outputEventsCount.add(out.size);
            out;
        }).flatMap(f => f.map(f => f));
        
    }
    override def postProcess(data: RDD[MeasuredEvent], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[MeasuredEvent] = {
        data
    }
}