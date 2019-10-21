package com.getjenny.analyzer.operators

import com.getjenny.analyzer.expressions._
import scalaz._
import Scalaz._
import com.getjenny.analyzer.atoms.ExceptionAtomic
import com.getjenny.starchat.services.{AnalyzerService, DecisionTableRuntimeItem}

/**
  * Created by Andrea Collamati <andrea@getjenny.com> on 20/09/2019.
  */


case class BayesCounters(val nQueriesOfStateTriggered: Int = 0, val nTotalQueriesTriggered: Int = 0)

/* Bayes Operator evaluate the P(S|Expr) = probability that a state S should be triggered
   if the Expr is true. This is done using the Whisperer Queries data set.
   Expr should be a boolean expression.
   Bayes operator must have only one children
 */
class BayesOperator(children: List[Expression]) extends AbstractOperator(children: List[Expression]) {
  val operatorName: String = "Bayes"

  override def toString: String = operatorName + "Operator(" + children.mkString(", ") + ")"

  def add(e: Expression, level: Int = 0): AbstractOperator = {

    // Unary operator check
    if (level == 0 && children.nonEmpty) throw OperatorException(operatorName + "Operator: should have only one argument")

    if (level === 0) new BayesOperator(e :: children)
    else {
      children.headOption match {
        case Some(t) =>
          t match {
            case c: AbstractOperator => new BayesOperator(c.add(e, level - 1) :: children.tail)
            case _ => throw OperatorException(operatorName + "Operator: trying to add to smt else than an operator")
          }
        case _ =>
          throw OperatorException(operatorName + "Operator: trying to add None instead of an operator")
      }
    }
  }

  def evaluate(query: String, data: AnalyzersDataInternal = AnalyzersDataInternal()): Result = {

    val triggerCondition = children.headOption match {
      case Some(t) => t
      case _ => throw OperatorException(operatorName + "Operator: operator argument is empty")
    }

    // Check trigger
    val triggerResult = triggerCondition.matches(query, data)
    if (triggerResult.score < 1.0d) {
      Result(score = 0.0d, data = triggerResult.data)
    }
    else {
      // Trigger Condition is true we can start to evaluate the condition on Whisperer Query
      val activeAnalyzeMap = AnalyzerService.analyzersMap.get(data.context.indexName) match {
        case Some(t) => t.analyzerMap
        case _ => throw ExceptionAtomic(operatorName + "Operator:active analyzer map not found, DT not posted")
      }

      val currentStateName = data.context.stateName

      val currentState: DecisionTableRuntimeItem = activeAnalyzeMap.get(currentStateName) match {
        case Some(t) => t
        case _ => throw ExceptionAtomic(operatorName + "Operator:state not found in map")
      }

      val nQueries = currentState.queries.length

      if (nQueries > 0) {
        val counters = activeAnalyzeMap.foldLeft(new BayesCounters()) {
          (cnt, elem) => {
            val stateName = elem._1
            val queries = elem._2.queries
            queries.foldLeft(cnt) {
              (cnt2, whispererQuery) => {
                // count queries that satisfy trigger condition totally and per state
                val triggerResult = triggerCondition.matches(whispererQuery, data)
                if (triggerResult.score === 1.0d) {
                  val totalTriggered = cnt2.nTotalQueriesTriggered + 1
                  val totalTriggeredInState = {
                    if (currentStateName == stateName) {
                      cnt2.nQueriesOfStateTriggered + 1
                    }
                    else {
                      cnt2.nQueriesOfStateTriggered
                    }
                  }
                  new BayesCounters(totalTriggeredInState, totalTriggered)
                }
                else
                  cnt2
              }
            }
          }
        }

        val bayesScore = counters.nTotalQueriesTriggered.toDouble match {
          case v: Double if v > 0 => counters.nQueriesOfStateTriggered.toDouble / v
          case v: Double if v == 0 => 1.0d
          case _ => throw ExceptionAtomic(operatorName + "Operator:totalQueries = 0 not expected here")
        }

        val msg = operatorName + "Operator: score = " + bayesScore + "(" + counters.nQueriesOfStateTriggered + "/" + counters.nTotalQueriesTriggered + ")"
        println(msg) //"TODO: remove me"

        Result(score = bayesScore, data = triggerResult.data)
      }
      else
        Result(score = 1.0d, data = triggerResult.data)
    }
  }
}