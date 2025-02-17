package Chainsaw.arithmetic.bitheap

import Chainsaw.{ChainsawFlatSpec, _}
import Chainsaw.arithmetic._
import Chainsaw.xilinx._
import spinal.core._
import spinal.core.sim._

import java.io.File
import scala.language.postfixOps
import scala.util.Random

class BitHeapTests extends ChainsawFlatSpec {

  behavior of "initializers"

  // FIXME: upperbound of genRectangularInfos is always the same
  //     following situations should be generated by the getRandomInfos method:
  //     1. mixed signedness
  //     2. diff time
  //     3. different weightLows and weightHighs
  def getRandomInfos = ArithInfoGenerator.genRectangularInfos(
    width        = 10,
    height       = 10,
    withNoise    = true,
    timeStrategy = RandomTimeDiff
  )

  def getRandomOperands =
    getRandomInfos.map(info => WeightedBigInt(BigInt(info.width, Random), info))

  def getRandomBitHeap = BitHeap.fromBigInts(getRandomOperands)

  def getEmptyBitHeap = BitHeap.fromHeights(Seq[Int](), 0, 0)

  it should "work" in {
    (0 until 1000).foreach { _ =>
      val weightedBigInts = getRandomOperands
      val bitHeap         = BitHeap.fromBigInts(weightedBigInts)
      val expected        = weightedBigInts.map(_.eval).sum
      val actual          = bitHeap.evalBigInt
      assert(
        actual == expected,
        s"\noperands:\n${weightedBigInts.mkString("\n")}\n " +
          s"heap:\n${bitHeap.heights.mkString(" ")}, weight = ${bitHeap.weightLow}\n " +
          s"expected = $expected, actual = $actual"
      )
    }
  }

  behavior of "BitHeap"

  it should "contributeHeapTo" in {
    (0 until 1000).foreach { _ =>
      val bitHeap0   = getRandomBitHeap
      val bitHeap1   = getRandomBitHeap
      val sumsBefore = bitHeap0.evalBigInt + bitHeap1.evalBigInt
      bitHeap0.contributeHeapTo(bitHeap1)
      val sumsAfter = bitHeap0.evalBigInt + bitHeap1.evalBigInt
      assert(bitHeap0.evalBigInt == 0) // bitHeap0 should be empty after move
      assert(
        sumsBefore == sumsAfter,
        s"before: $sumsBefore, after: $sumsAfter"
      ) // sums should be the same, before and after
    }
  }

  it should "addition" in {
    (0 until 1000).foreach { _ =>
      val bitHeap0     = getRandomBitHeap
      val bitHeap1     = getRandomBitHeap
      val valueBefore0 = bitHeap0.evalBigInt
      val sumHeap      = bitHeap0 + bitHeap1
      val valueAfter0  = bitHeap0.evalBigInt
      assert(valueBefore0 == valueAfter0)
      assert(sumHeap.evalBigInt == bitHeap0.evalBigInt + bitHeap1.evalBigInt)
    }
  }

  it should "getSubHeap" in {
    (0 until 1000).foreach { _ =>
      val stepSolution = CompressorStepSolution(
        "Compressor4to2",
        8,
        0,
        CompressorScores(0, 0, 0, 0)
      )
      val bitHeap = getRandomBitHeap
      val small =
        BitHeap.fromHeights(Seq(2, 1, 1, 7), bitHeap.weightLow, bitHeap.time)
      bitHeap.absorbHeapFrom(small)
      val valueBefore = bitHeap.evalBigInt
      val sub         = bitHeap.getSub(Seq(2, 1, 1, 7), 0)
      val valueAfter  = sub.evalBigInt + bitHeap.evalBigInt
      assert(valueBefore == valueAfter)
    }
  }

  it should "allocate" in {
    (0 until 1000).foreach { _ =>
      val bitHeap = getRandomBitHeap
      val randomValue = Random.nextInt(
        (bitHeap.maxValue >> bitHeap.weightLow).toInt
      ) << bitHeap.weightLow
      bitHeap.allocate(randomValue)
      assert(bitHeap.evalBigInt == randomValue)
    }
  }

  it should "constant addition" in {
    (0 until 1000).foreach { _ =>
      // TODO: more bitheap and compressors of different shapes
      val bitHeap     = BitHeap.fromHeights(Seq.fill(10)(10), 0, 0)
      val valueBefore = bitHeap.evalBigInt
      val constant0   = BigInt(20, Random)
      bitHeap.addConstant(constant0)
      bitHeap.absorbConstant()
      val valueAfterAdd = bitHeap.evalBigInt
      //      println(s"valueBefore = $valueBefore, valueAfter = $valueAfterAdd, constant = $constant0")
      assert(valueAfterAdd == valueBefore + constant0)
      val constant1 = Random.nextInt(bitHeap.maxValue.toInt)
      bitHeap.addConstant(-constant1)
      bitHeap.absorbConstant()
      val validLength   = bitHeap.positiveLength
      val valueAfterSub = bitHeap.evalBigInt
      val golden        = valueAfterAdd - constant1
      //      println(s"valueAfterAdd = $valueAfterAdd, valueAfterSub = $valueAfterSub, golden = $golden, constant = $constant1")
      if (golden >= 0)
        assert(
          valueAfterSub.mod(pow2(validLength)) == golden.mod(pow2(validLength))
        )
    }
  }

  val solution200_6 = CompressorFullSolution(
    Seq(
      CompressorStageSolution(
        (0 until 200).map(i =>
          CompressorStepSolution(
            compressorName   = "Compressor6to3",
            width            = 1,
            columnIndex      = i,
            compressorScores = CompressorScores(0, 0, 0, 0)
          )
        ),
        -1,
        pipelined = true
      ),
      CompressorStageSolution(
        (0 until 2).map(i =>
          CompressorStepSolution(
            "Compressor3to1",
            96,
            96 * i,
            CompressorScores(0, 0, 0, 0)
          )
        ),
        -1,
        pipelined = true
      )
    )
  )

  behavior of "implSoft"

  it should "work on the whole heap" in {
    (0 until 1000).foreach { _ =>
      // TODO: more bitheap and compressors of different shapes
      val bitHeap     = BitHeap.fromHeights(Seq.fill(200)(6), 0, 0)
      val valueBefore = bitHeap.evalBigInt
      val bitHeapNext = bitHeap.implAllSoft(solution200_6)
      val valueAfter  = bitHeapNext.evalBigInt
      assert(valueBefore == valueAfter)
    }
  }

  behavior of "implHard"

  // TODO: more bitheap and solutions of different shapes
  it should "work on the whole heap" in {
    case class Add200_6() extends Component {
      val dataIn = in Vec (UInt(200 bits), 6)
      val bitHeap =
        BitHeap.fromUInts(dataIn.map(WeightedUInt(_, ArithInfo(200, 0))))
      val dataOut = bitHeap.implAllHard(solution200_6).toUInts
      dataOut.foreach(out(_))
    }
    // gen
    SpinalConfig().generateVerilog(Add200_6())
    // sim
    SimConfig.withFstWave.compile(Add200_6()).doSim { dut =>
      val data    = Seq.fill(6)(BigInt(200, Random))
      val valueIn = data.sum
      dut.clockDomain.forkStimulus(2)
      dut.clockDomain.waitSampling()
      dut.dataIn.zip(data).foreach({ case (in, value) => in #= value })
      dut.clockDomain.waitSampling(5)
      val valueOut = dut.dataOut.map(_.toBigInt).sum
      assert(valueIn == valueOut, s"values: in = $valueIn, out = $valueOut")
      logger.info("passed")
    }

    if (hasVivado) VivadoSynth(Add200_6(), "synthAdd200_6")
  }

  behavior of "BitHeaps"

  // FIXME: complement implementation is not suitable for implSoft
//  it should "init from mixed operands correctly" in {
//    val infos = Seq(
//      ArithInfo(64, 128),
//      ArithInfo(64, 192),
//      ArithInfo(64, 160),
//      ArithInfo(64, 160, isPositive = false),
//      ArithInfo(64, 160, isPositive = false),
//      ArithInfo(32, 192),
//      ArithInfo(32, 192),
//      ArithInfo(1, 224)
//    )
//
//    (0 until 100).foreach { i =>
//
//      val bitHeaps = BitHeapGroup.fromInfos(infos)
//      println(bitHeaps)
//      println(s"constant = ${bitHeaps.constant}")
//      val valueBefore = bitHeaps.evalBigInt
//      println(s"value = $valueBefore")
//
//      bitHeaps.absorbConstant()
//      println(bitHeaps)
//      println(s"constant = ${bitHeaps.constant}")
//      val valueAfter = bitHeaps.evalBigInt
//      println(s"value = $valueAfter")
//
//      assert(
//        valueBefore == valueAfter,
//        s"failed at $i-th case, before = $valueBefore, after = $valueAfter"
//      )
//
//      val solution  = GreedSolver.solveAll(bitHeaps)
//      println(bitHeaps)
//      val compressed = bitHeaps.bitHeaps.head.implAllSoft(solution)
//      println(compressed)
//      val valueEval = compressed.evalBigInt
//      assert(
//        valueBefore == valueEval,
//        s"values: before = $valueBefore, after = $valueAfter, eval = $valueEval"
//      )
//    }
//  }

  behavior of "CompressorFullSolution"

  it should "(de)serialization" in {
    val solution: CompressorFullSolution = CompressorFullSolution(
      Seq(
        CompressorStageSolution(
          Seq(
            CompressorStepSolution(
              "Compressor2117",
              1,
              0,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              2,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              4,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              6,
              CompressorScores(0, 0, 0, 0)
            )
          ),
          -1,
          pipelined = true
        ),
        CompressorStageSolution(
          Seq(
            CompressorStepSolution(
              "Compressor2117",
              1,
              0,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              2,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              4,
              CompressorScores(0, 0, 0, 0)
            ),
            CompressorStepSolution(
              "Compressor2117",
              1,
              6,
              CompressorScores(0, 0, 0, 0)
            )
          ),
          -1,
          pipelined = true
        )
      )
    )

    val file = new File(compressorSolutionOutputDir, "solution")
    solution.save(file)
    val deserialized = CompressorFullSolution.load(file)
    assert(solution.equals(deserialized))
  }
}
