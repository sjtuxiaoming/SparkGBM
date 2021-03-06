package org.apache.spark.ml.gbm

import org.apache.spark.rdd.RDD

/**
  * trait for evaluation function to compute the metric
  */
trait EvalFunc extends Serializable {

  /**
    * compute the metric in a batch fashion, MUST NOT change the storage level of input data
    *
    * @param data scored dataset containing (weight, label, raw, score)
    * @return the metric value
    */
  def compute(data: RDD[(Double, Array[Double], Array[Double], Array[Double])]): Double

  def isLargerBetter: Boolean

  def name: String
}


trait ScalarEvalFunc extends EvalFunc {

  def computeImpl(data: RDD[(Double, Double, Double, Double)]): Double

  final override def compute(data: RDD[(Double, Array[Double], Array[Double], Array[Double])]): Double = {
    val scalarData = data.map { case (weight, label, raw, score) =>
      require(label.length == 1 && raw.length == 1 && score.length == 1)
      (weight, label.head, raw.head, score.head)
    }
    computeImpl(scalarData)
  }
}


/**
  * trait for incremental (aggregation) evaluation function
  */
trait IncEvalFunc extends EvalFunc {

  final override def compute(data: RDD[(Double, Array[Double], Array[Double], Array[Double])]): Double = {
    IncEvalFunc.compute(data, Array(this), 2).head._2
  }

  /** update the internal statistics aggregator */
  def update(weight: Double, label: Array[Double], raw: Array[Double], score: Array[Double]): Unit

  /** merge the internal aggregators */
  def merge(other: IncEvalFunc): Unit

  /** compute the final metric value */
  def getValue: Double

  /** initial the internal statistics */
  def init: Unit
}


trait ScalarIncEvalFunc extends IncEvalFunc {

  def update(weight: Double, label: Double, raw: Double, score: Double): Unit

  final override def update(weight: Double, label: Array[Double], raw: Array[Double], score: Array[Double]): Unit = {
    require(label.length == 1 && raw.length == 1 && score.length == 1)
    update(weight, label.head, raw.head, score.head)
  }
}


private[gbm] object IncEvalFunc {
  def compute(data: RDD[(Double, Array[Double], Array[Double], Array[Double])],
              evals: Array[IncEvalFunc],
              depth: Int): Map[String, Double] = {
    evals.foreach(_.init)

    data.treeAggregate[Array[IncEvalFunc]](evals)(
      seqOp = {
        case (evals, (weight, label, rawScore, score)) =>
          evals.foreach(eval => eval.update(weight, label, rawScore, score))
          evals
      }, combOp = {
        case (evals1, evals2) =>
          require(evals1.length == evals2.length)
          evals1.zip(evals2).foreach {
            case (eval1, eval2) =>
              eval1.merge(eval2)
          }
          evals1
      }, depth = depth)
      .map(e => (e.name, e.getValue))
      .toMap
  }
}


/**
  * trait for simple element-wise evaluation function
  */
trait SimpleEvalFunc extends ScalarIncEvalFunc {

  protected var count = 0.0
  protected var avg = 0.0

  /** compute the metric on each instance */
  def compute(label: Double, score: Double): Double

  override def update(weight: Double, label: Double, rawScore: Double, score: Double): Unit = {
    if (weight > 0) {
      count += weight
      val diff = compute(label, score) - avg
      avg += diff / count
    }
  }

  override def merge(other: IncEvalFunc): Unit = {
    val o = other.asInstanceOf[SimpleEvalFunc]
    if (o.count > 0) {
      count += o.count
      val diff = o.avg - avg
      avg += o.count / count * diff
    }
  }

  override def getValue: Double = {
    avg
  }

  override def init: Unit = {
    count = 0.0
    avg = 0.0
  }
}


/**
  * Mean square error
  */
class MSEEval extends SimpleEvalFunc {
  override def compute(label: Double, score: Double): Double = {
    (label - score) * (label - score)
  }

  override def isLargerBetter: Boolean = false

  override def name = "MSE"
}


/**
  * Root mean square error
  */
class RMSEEval extends SimpleEvalFunc {
  override def compute(label: Double, score: Double): Double = {
    (label - score) * (label - score)
  }

  override def getValue: Double = {
    math.sqrt(avg)
  }

  override def isLargerBetter: Boolean = false

  override def name = "RMSE"
}


/**
  * Mean absolute error
  */
class MAEEval extends SimpleEvalFunc {
  override def compute(label: Double, score: Double): Double = {
    (label - score).abs
  }

  override def isLargerBetter: Boolean = false

  override def name = "MAE"
}


/**
  * R2 score
  */
class R2Eval extends ScalarIncEvalFunc {

  protected var count = 0.0
  private var avgLabel = 0.0
  private var avgLabel2 = 0.0
  private var avgError2 = 0.0

  /** update the internal statistics aggregator */
  override def update(weight: Double, label: Double, rawScore: Double, score: Double): Unit = {
    if (weight > 0) {
      count += weight

      var diff = label - avgLabel
      avgLabel += diff / count

      diff = label * label - avgLabel2
      avgLabel2 += diff / count

      diff = (label - score) * (label - score) - avgError2
      avgError2 += diff / count
    }
  }

  /** merge the internal aggregators */
  override def merge(other: IncEvalFunc): Unit = {
    val o = other.asInstanceOf[R2Eval]
    if (o.count > 0) {
      count += o.count

      var diff = o.avgLabel - avgLabel
      avgLabel += o.count / count * diff

      diff = o.avgLabel2 - avgLabel2
      avgLabel2 += o.count / count * diff

      diff = o.avgError2 - avgError2
      avgError2 += o.count / count * diff
    }
  }

  /** compute the final metric value */
  override def getValue: Double = {
    1 - avgError2 / (avgLabel2 - avgLabel * avgLabel)
  }

  /** initial the internal statistics */
  override def init: Unit = {
    count = 0.0
    avgLabel = 0.0
    avgLabel2 = 0.0
    avgError2 = 0.0
  }

  override def isLargerBetter: Boolean = true

  override def name: String = "R2"
}


/**
  * Log-loss or Cross Entropy
  */
class LogLossEval extends SimpleEvalFunc {

  override def compute(label: Double, score: Double): Double = {
    require(label >= 0 && label <= 1)

    // probability of positive class
    val ppos = 1.0 / (1.0 + math.exp(-score))

    val pneg = 1.0 - ppos

    val eps = 1e-16

    if (ppos < eps) {
      -label * math.log(eps) - (1.0 - label) * math.log(1.0 - eps)
    } else if (pneg < eps) {
      -label * math.log(1.0 - eps) - (1.0 - label) * math.log(eps)
    } else {
      -label * math.log(ppos) - (1.0 - label) * math.log(pneg)
    }
  }

  override def isLargerBetter: Boolean = false

  override def name = "LogLoss"
}


/**
  * Classification error
  *
  * @param threshold threshold in binary classification prediction
  */
class ErrorEval(val threshold: Double) extends SimpleEvalFunc {
  require(threshold >= 0 && threshold <= 1)

  override def compute(label: Double, score: Double): Double = {
    require(label == 0 || label == 1)

    if (label == 0 && score <= threshold) {
      0.0
    } else if (label == 1 && score > threshold) {
      0.0
    } else {
      1.0
    }
  }

  override def isLargerBetter = false

  override def name = s"Error-$threshold"
}


/**
  * Area under curve
  *
  * @param numBins number of bins for approximate computation
  */
class AUCEval(val numBins: Int) extends ScalarIncEvalFunc {
  require(numBins >= 16)

  def this() = this(1 << 16)

  private val histPos = Array.ofDim[Double](numBins)
  private val histNeg = Array.ofDim[Double](numBins)

  override def update(weight: Double, label: Double, rawScore: Double, score: Double): Unit = {
    require(label == 0 || label == 1)

    // probability of positive class
    val ppos = 1.0 / (1.0 + math.exp(-score))

    val index = math.min((ppos * numBins).floor.toInt, numBins - 1)

    if (label == 1) {
      histPos(index) += weight
    } else {
      histNeg(index) += weight
    }
  }

  override def merge(other: IncEvalFunc): Unit = {
    val o = other.asInstanceOf[AUCEval]
    require(numBins == o.numBins)
    var i = 0
    while (i < numBins) {
      histPos(i) += o.histPos(i)
      histNeg(i) += o.histNeg(i)
      i += 1
    }
  }

  override def getValue: Double = {
    val numPos = histPos.sum
    val numNeg = histNeg.sum

    var i = 0
    while (i < numBins) {
      histPos(i) /= numPos
      histNeg(i) /= numNeg
      i += 1
    }

    var truePos = 0.0
    var falsePos = 0.0

    var auc = 0.0
    i = numBins - 1
    while (i >= 0) {
      // trapezoidal area between point (falsePos, truePos)
      // <-> point (falsePos + histPos(i), truePos + histPos(i)) on ROC
      auc += histNeg(i) * (truePos + histPos(i) / 2)
      truePos += histPos(i)
      falsePos += histNeg(i)
      i -= 1
    }
    auc += (1.0 - falsePos) * (1.0 + truePos) / 2
    auc
  }

  override def init: Unit = {
    var i = 0
    while (i < numBins) {
      histPos(i) = 0.0
      histNeg(i) = 0.0
      i += 1
    }
  }

  override def isLargerBetter: Boolean = true

  override def name: String = "AUC"
}

