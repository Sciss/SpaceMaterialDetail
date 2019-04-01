/*
 *  ModPhaseDiff.scala
 *  (SpaceMaterialDesign)
 *
 *  Copyright (c) 2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.spacematerialdesign

import de.sciss.fscape.GE
import de.sciss.fscape.lucre.FScape
import de.sciss.fscape.modules.Module
import de.sciss.lucre.stm.Sys
import de.sciss.synth.proc.Widget

import scala.Predef.{any2stringadd => _}

object ModPhaseDiff extends Module {
  val name = "Phase Differences"

  def apply[S <: Sys[S]]()(implicit tx: S#Tx): FScape[S] = {
    import de.sciss.fscape.lucre.graph.Ops._
    import de.sciss.fscape.graph.{AudioFileIn => _, AudioFileOut => _, ImageFileIn => _, ImageFileOut => _, ImageFileSeqIn => _, ImageFileSeqOut => _, _}
    import de.sciss.fscape.lucre.graph._
    val f = FScape[S]()
    import de.sciss.fscape.lucre.MacroImplicits._
    f.setGraph {
      // version 25-Mar-2019 - Mellite 2.33
      // written by Hanns Holger Rutz
      // license: CC BY-SA 4.0

      val firstFrameIn  = "frame-first" .attr(1)
      val lastFrameIn   = "frame-last"  .attr(1)
      val resampleIn    = "resample-in" .attr(1)
      val resampleOut   = "resample-out".attr(1)
      val noise         = "noise"       .attr(0.0)

      val numFramesIn   = (firstFrameIn absDif lastFrameIn) + (1: GE)
      val frameInStep   = (lastFrameIn >= firstFrameIn) * 2 - 1
      val indicesIn     = ArithmSeq(firstFrameIn, frameInStep, length = numFramesIn)
      val numFramesOut  = (numFramesIn * resampleIn - 1) * resampleOut
      val indicesOut    = ArithmSeq(1, length = numFramesOut)
      val useWindow     = true
      val center        = true
      val diff          = true
      val cosh          = true
      val coshGain      = 10.0
      val hpf           = false

      val videoIn0  = ImageFileSeqIn("in", indices = indicesIn)

      val wIn       = videoIn0.width  // 1920
      val hIn       = videoIn0.height // 1080

      wIn.poll(0, "width")
      hIn.poll(0, "height")

      val videoIn   = If (resampleIn sig_== 1) Then {
        videoIn0
      } Else {
        ResampleWindow(videoIn0, size = wIn*hIn,
          factor = resampleIn, minFactor = resampleIn,
          rollOff = 0.9, kaiserBeta = 12, zeroCrossings = 15
        )
      }

      val fftSize   = ((wIn min hIn) / 2).nextPowerOfTwo
      val fftSizeH  = fftSize/2
      val fftSizeSq = fftSize.squared

      val mono: GE = {
        val r = videoIn.out(0)
        val g = videoIn.out(1)
        val b = videoIn.out(2)
        r * 0.2126 + g * 0.7152 + b * 0.0722
      }

      val crop: GE = {
        val dw        = wIn - fftSize
        val dwStart   = dw/2
        val dwStop    = -(dw - dwStart)
        val h         = ResizeWindow(mono, size = wIn, start = dwStart, stop = dwStop)
        val dh        = hIn - fftSize
        val dhStart0  = dh/2
        val dhStop0   = -(dh - dhStart0)
        val dhStart   = dhStart0 * fftSize
        val dhStop    = dhStop0  * fftSize
        ResizeWindow(h, size = fftSize * hIn, start = dhStart, stop = dhStop)
      }

      def mkWindow(in: GE): GE = {
        val h = in *              GenWindow(size = fftSize, shape = GenWindow.Hann)
        val v = h  * RepeatWindow(GenWindow(size = fftSize, shape = GenWindow.Hann), num = fftSize)
        v
      }

      val frameSize = fftSize.squared
      // XXX TODO --- using crop directly without windowing has very interesting phase images
      val windowed  = if (!useWindow) crop else mkWindow(crop)
      val seqA      = BufferMemory(windowed, frameSize)
      val seqB      = windowed.drop         (frameSize)
      val fftA      = Real2FullFFT(seqA, rows = fftSize, columns = fftSize)
      val fftB      = Real2FullFFT(seqB, rows = fftSize, columns = fftSize)

      val conjA     = fftA .complex.conj  // A is to be shift against B!
      val conv      = fftB.complex * conjA
      val convMagR  = conv .complex.mag.max(1.0e-06).reciprocal
      val convBuf   = BufferMemory(conv, size = frameSize)
      val elemNorm  = convBuf * RepeatWindow(convMagR)
      val iFFT0     = Real2FullIFFT(in = elemNorm, rows = fftSize, columns = fftSize)
      val iFFT      = iFFT0 / fftSize

      val phaseBase = iFFT
      val phaseDiff0: GE = if (!diff) phaseBase else {
        val phaseA  = BufferMemory(phaseBase, frameSize)
        val phaseB  = phaseBase.drop         (frameSize)
        phaseB - phaseA
      }

      val phaseDiff1 = if (!center) phaseDiff0 else AffineTransform2D.translate(phaseDiff0,
        widthIn  = fftSize, heightIn  = fftSize,
        widthOut = fftSize, heightOut = fftSize,
        tx = fftSizeH, ty = fftSizeH, wrap = 1, zeroCrossings = 0)

      val phaseDiff2 = if (!hpf) phaseDiff1 else HPF(phaseDiff1, freqN = 0.02) // + phaseDiff1

      val phaseDiff3 = if (!cosh) phaseDiff2 else
        (phaseDiff2 * coshGain).cosh /* .reciprocal */ - 1.0 // absDif 0.0

      val phaseDiff = phaseDiff3

      val phaseDiffI0 = ARCWindow(phaseDiff, size = fftSizeSq)

      val phaseDiffI1 = If (resampleOut sig_== 1) Then phaseDiffI0 Else {
        ResampleWindow(phaseDiffI0, size = fftSizeSq,
          factor = resampleOut, minFactor = resampleOut,
          rollOff = 0.9, kaiserBeta = 12, zeroCrossings = 15)
      }

      val phaseDiffI2 = If (noise <= 0) Then phaseDiffI1 Else { phaseDiffI1 + WhiteNoise(noise) }

      val phaseDiffI = phaseDiffI2.clip(0.0, 1.0)

      val composite = phaseDiffI // left + right
      ImageFileSeqOut(in = composite, key = "out",
        width = fftSize /* * 2 */, height = fftSize,
        indices = indicesOut)

      val TotalSize = numFramesOut * fftSizeSq
      val prog = Frames(composite) / TotalSize
      Progress(in = prog, trig = Metro(fftSizeSq))
    }
    f
  }

  def ui[S <: Sys[S]]()(implicit tx: S#Tx): Widget[S] = {
    import de.sciss.lucre.expr.ExOps._
    import de.sciss.lucre.expr.graph._
    import de.sciss.lucre.swing.graph._
    val w = Widget[S]()
    import de.sciss.synth.proc.MacroImplicits._
    w.setGraph {
      // version 01-Apr-2019
      val r = Runner("run")
      val m = r.messages
      m.changed.filter(m.nonEmpty) ---> Println(m.mkString("\n"))

      def mkLabel(text: String) = {
        val l = Label(text)
        l.hAlign = Align.Trailing
        l
      }

      val lbIn = mkLabel("Input Image Sequence:")
      val pfIn = PathField(PathField.Open)
      pfIn.title = "Input Image Sequence"
      pfIn.value <--> Artifact("run:in")

      val lbOut  = mkLabel("Output Image Sequence:")
      val pfOut = PathField(PathField.Save)
      pfOut.title = "Output Image Sequence"
      pfOut.value <--> Artifact("run:out")

      val lbInFirstIdx = mkLabel("Input First Frame:")
      val ggInFirstIdx = IntField()
      ggInFirstIdx.min = 0
      ggInFirstIdx.value <--> "run:frame-first".attr(1)
      val lbInLastIdx  = mkLabel("Input Last Frame:")
      val ggInLastIdx = IntField()
      ggInLastIdx.min = 0
      ggInLastIdx.value <--> "run:frame-last".attr(1)
      //val pTop = FlowPanel(lbIn, pfIn)

      val lbResampleIn = mkLabel("Input Resampling Factor:")
      val ggResampleIn = IntField()
      ggResampleIn.min = 1
      ggResampleIn.value <--> "run:resample-in".attr(1)

      val lbResampleOut = mkLabel("Output Resampling Factor:")
      val ggResampleOut = IntField()
      ggResampleOut.min = 1
      ggResampleOut.value <--> "run:resample-out".attr(1)

      val lbNoise = mkLabel("Noise Amount (0…1):")
      val ggNoise = DoubleField()
      ggNoise.min = 0.0
      ggNoise.max = 1.0
      ggNoise.value <--> "run:noise".attr(0.0)

      // def left(c: Component*): Component = {
      //   val f = FlowPanel(c: _*)
      //   f.align = Align.Leading
      //   f.vGap = 0
      //   f
      // }

      val pTop = GridPanel(
        lbIn         , pfIn,
        lbOut        , pfOut,
        lbInFirstIdx , ggInFirstIdx,
        lbInLastIdx  , ggInLastIdx,
        lbResampleIn , ggResampleIn,
        lbResampleOut, ggResampleOut,
        lbNoise      , ggNoise,
      )
      pTop.compactColumns = true
      pTop.columns = 2


      val ggRender  = Button(" Render ")
      val ggCancel  = Button(" X ")
      ggCancel.tooltip = "Cancel Rendering"
      val pb        = ProgressBar()
      ggRender.clicked ---> r.run
      ggCancel.clicked ---> r.stop
      val stopped = r.state sig_== 0
      ggRender.enabled = stopped
      ggCancel.enabled = !stopped
      pb.value = (r.progress * 100).toInt
      val bot = BorderPanel(
        center  = pb,
        east    = {
          val f = FlowPanel(ggCancel, ggRender)
          f.vGap = 0
          f
        }
      )
      bot.vGap = 0
      val bp = BorderPanel(
        north = pTop,
        south = bot
      )
      bp.vGap = 8
      bp.border = Border.Empty(8, 8, 0, 4)
      bp
    }
    w
  }
}