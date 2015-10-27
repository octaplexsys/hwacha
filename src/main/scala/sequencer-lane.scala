package hwacha

import Chisel._
import cde.Parameters
import DataGating._

class SequencerIO(implicit p: Parameters) extends VXUBundle()(p) {
  val exp = Valid(new SeqOp)
  val vipu = Valid(new SeqVIPUOp)
  val vpu = Valid(new SeqVPUOp)
}

class LaneSequencer(implicit p: Parameters) extends VXUModule()(p) with BankLogic {
  val io = new Bundle {
    val cfg = new HwachaConfigIO().flip
    val op = Valid(new IssueOp).flip
    val master = new MasterSequencerIO().flip
    val seq = new SequencerIO
    val vmu = new VMUIO
    val ticker = new TickerIO().flip

    val dpla = new CounterLookAheadIO
    val dqla = Vec.fill(nVDUOperands){new CounterLookAheadIO}
    val dila = new CounterLookAheadIO
    val dfla = new CounterLookAheadIO
    val gpla = new CounterLookAheadIO
    val gqla = new CounterLookAheadIO
    val pla = new BPQLookAheadIO
    val lla = new CounterLookAheadIO
    val sla = new BRQLookAheadIO
    val lreq = new CounterLookAheadIO
    val sreq = new CounterLookAheadIO

    val lpred = Decoupled(Bits(width=nPredSet))
    val spred = Decoupled(Bits(width=nPredSet))

    val lack = new LaneAckIO().flip
    val dack = new DCCAckIO().flip

    val debug = new Bundle {
      val valid = Vec.fill(nSeq){Bool(OUTPUT)}
      val e = Vec.fill(nSeq){new SeqEntry}.asOutput
      val dhazard_raw_vlen = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_pred_vp = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_pred_vs1 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_pred_vs2 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_pred_vs3 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_vs1 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_vs2 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_raw_vs3 = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_war = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard_waw = Vec.fill(nSeq){Bool(OUTPUT)}
      val dhazard = Vec.fill(nSeq){Bool(OUTPUT)}
      val bhazard = Vec.fill(nSeq){Bool(OUTPUT)}
      val shazard = Vec.fill(nSeq){Bool(OUTPUT)}
      val use_mask_sreg_global = Vec.fill(nGOPL){Bits(OUTPUT, maxSRegGlobalTicks+nBanks-1)}
      val use_mask_xbar = Vec.fill(nGOPL){Bits(OUTPUT, maxXbarTicks+nBanks-1)}
      val use_mask_vimu = Bits(OUTPUT, maxVIMUTicks+nBanks-1)
      val use_mask_vfmu = Vec.fill(nVFMU){Bits(OUTPUT, maxVFMUTicks+nBanks-1)}
      val use_mask_vfcu = Bits(OUTPUT, maxVFCUTicks+nBanks-1)
      val use_mask_vfvu = Bits(OUTPUT, maxVFVUTicks+nBanks-1)
      val use_mask_vgu = Bits(OUTPUT, maxVGUTicks+nBanks-1)
      val use_mask_vqu = Bits(OUTPUT, maxVQUTicks+nBanks-1)
      val use_mask_wport_sram = Vec.fill(nWSel){Bits(OUTPUT, maxWPortLatency+nBanks-1)}
      val use_mask_wport_pred = Bits(OUTPUT, maxPredWPortLatency+nBanks-1)
      val pred_first = Vec.fill(nSeq){Bool(OUTPUT)}
      val consider = Vec.fill(nSeq){Bool(OUTPUT)}
      val first_sched = Vec.fill(nSeq){Bool(OUTPUT)}
      val second_sched = Vec.fill(nSeq){Bool(OUTPUT)}
    }
  }

  val mv = io.master.state.valid
  val me = io.master.state.e
  val head = io.master.state.head

  val v = Vec.fill(nSeq){Reg(init=Bool(false))}
  val e = Vec.fill(nSeq){Reg(new SeqEntry)}

  def stripfn(vl: UInt, vcu: Bool, fn: VFn) = {
    val max_strip = Mux(vcu, UInt(nBatch << 1), UInt(nBatch))
    Mux(vl > max_strip, max_strip, vl)
  }

  ///////////////////////////////////////////////////////////////////////////
  // data hazard checking helpers

  val dhazard = new {
    val vlen_check_ok =
      Vec((0 until nSeq).map { r =>
        Vec((0 until nSeq).map { c =>
          if (r != c) e(UInt(r)).vlen > e(UInt(c)).vlen
          else Bool(true) }) })

    def wsram_mat(fn: RegFn, pfn: PRegIdFn) =
      (0 until nSeq) map { r =>
        Vec((0 until maxWPortLatency) map { l =>
          io.ticker.sram.write(l).valid && fn(me(r).base).valid && fn(me(r).base).is_vector() &&
          io.ticker.sram.write(l).bits.addr === pfn(e(r).reg).id }) }
    val wsram_mat_vs1 = wsram_mat(reg_vs1, pregid_vs1) map { m => Vec(m.slice(expLatency, maxWPortLatency)) }
    val wsram_mat_vs2 = wsram_mat(reg_vs2, pregid_vs2) map { m => Vec(m.slice(expLatency, maxWPortLatency)) }
    val wsram_mat_vs3 = wsram_mat(reg_vs3, pregid_vs3) map { m => Vec(m.slice(expLatency, maxWPortLatency)) }
    val wsram_mat_vd = wsram_mat(reg_vd, pregid_vd)

    def wpred_mat(fn: RegFn, pfn: PRegIdFn) =
      (0 until nSeq) map { r =>
        Vec((0 until maxPredWPortLatency) map { l =>
          io.ticker.pred.write(l).valid && fn(me(r).base).valid && fn(me(r).base).is_pred() &&
          io.ticker.pred.write(l).bits.addr === pfn(e(r).reg).id }) }
    val wpred_mat_vp = wpred_mat(reg_vp, pregid_vp) map { m => Vec(m.slice(expLatency, maxPredWPortLatency)) }
    val wpred_mat_vs1 = wpred_mat(reg_vs1, pregid_vs1) map { m => Vec(m.slice(expLatency, maxPredWPortLatency)) }
    val wpred_mat_vs2 = wpred_mat(reg_vs2, pregid_vs2) map { m => Vec(m.slice(expLatency, maxPredWPortLatency)) }
    val wpred_mat_vs3 = wpred_mat(reg_vs3, pregid_vs3) map { m => Vec(m.slice(expLatency, maxPredWPortLatency)) }
    val wpred_mat_vd = wpred_mat(reg_vd, pregid_vd)

    def wport_lookup(row: Vec[Bool], level: UInt) =
      Vec((row zipWithIndex) map { case (r, i) => r && UInt(i) > level })

    val raw =
      (0 until nSeq).map { r =>
        (me(r).raw.toBits & ~vlen_check_ok(r).toBits).orR ||
        wpred_mat_vp(r).toBits.orR ||
        wpred_mat_vs1(r).toBits.orR || wsram_mat_vs1(r).toBits.orR ||
        wpred_mat_vs2(r).toBits.orR || wsram_mat_vs2(r).toBits.orR ||
        wpred_mat_vs3(r).toBits.orR || wsram_mat_vs3(r).toBits.orR }
    val war =
      (0 until nSeq).map { r =>
        (me(r).war.toBits & ~vlen_check_ok(r).toBits).orR }
    val waw =
      (0 until nSeq).map { r =>
        (me(r).waw.toBits & ~vlen_check_ok(r).toBits).orR ||
        wport_lookup(wpred_mat_vd(r), me(r).wport.pred).toBits.orR ||
        wport_lookup(wsram_mat_vd(r), me(r).wport.sram).toBits.orR }

    val check =
      (0 until nSeq).map { r =>
        raw(r) || war(r) || waw(r) }

    def debug = {
      io.debug.dhazard_raw_vlen := Vec((0 until nSeq) map { r => (me(r).raw.toBits & ~vlen_check_ok(r).toBits).orR })
      io.debug.dhazard_raw_pred_vp := Vec((0 until nSeq) map { r => wpred_mat_vp(r).toBits.orR })
      io.debug.dhazard_raw_pred_vs1 := Vec((0 until nSeq) map { r => wpred_mat_vs1(r).toBits.orR })
      io.debug.dhazard_raw_pred_vs2 := Vec((0 until nSeq) map { r => wpred_mat_vs2(r).toBits.orR })
      io.debug.dhazard_raw_pred_vs3 := Vec((0 until nSeq) map { r => wpred_mat_vs3(r).toBits.orR })
      io.debug.dhazard_raw_vs1 := Vec((0 until nSeq) map { r => wsram_mat_vs1(r).toBits.orR })
      io.debug.dhazard_raw_vs2 := Vec((0 until nSeq) map { r => wsram_mat_vs2(r).toBits.orR })
      io.debug.dhazard_raw_vs3 := Vec((0 until nSeq) map { r => wsram_mat_vs3(r).toBits.orR })
      io.debug.dhazard_war := war
      io.debug.dhazard_waw := waw
      io.debug.dhazard := check
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // bank hazard checking helpers

  val bhazard = new {
    // tail (shift right by one) because we are looking one cycle in the future
    val rport_mask = Vec(io.ticker.sram.read.tail map { _.valid })
    val wport_sram_mask = Vec(io.ticker.sram.write.tail map { _.valid })
    val wport_pred_mask = Vec(io.ticker.pred.write.tail map { _.valid })

    val check =
      (0 until nSeq) map { r =>
        me(r).rports.orR && rport_mask.reduce(_ | _) ||
        me(r).wport.sram.orR && wport_sram_mask(me(r).wport.sram) ||
        me(r).wport.pred.orR && wport_pred_mask(me(r).wport.pred)
      }

    def debug = {
      io.debug.bhazard := check
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // structural hazard checking helpers

  val shazard = new {
    def use_mask_lop[T <: LaneOp](lops: Vec[ValidIO[T]], fn: ValidIO[T]=>Bool) = {
      val mask =
        (lops zipWithIndex) map { case (lop, i) =>
          dgate(fn(lop), UInt(strip_to_bmask(lop.bits.strip) << UInt(i), lops.size+nBanks-1))
        } reduce(_|_)
      mask >> UInt(1) // shift right by one because we are looking one cycle in the future
    }
    def use_mask_lop_valid[T <: LaneOp](lops: Vec[ValidIO[T]]) =
      use_mask_lop(lops, (lop: ValidIO[T]) => lop.valid)
    val use_mask_sreg_global = io.ticker.sreg.global map { use_mask_lop_valid(_) }
    val use_mask_xbar = io.ticker.xbar map { use_mask_lop_valid(_) }
    val use_mask_vimu = use_mask_lop_valid(io.ticker.vimu)
    val use_mask_vfmu = io.ticker.vfmu map { use_mask_lop_valid(_) }
    val use_mask_vfcu = use_mask_lop_valid(io.ticker.vfcu)
    val use_mask_vfvu = use_mask_lop_valid(io.ticker.vfvu)
    val use_mask_vgu = use_mask_lop_valid(io.ticker.vgu)
    val use_mask_vqu = use_mask_lop_valid(io.ticker.vqu)
    val use_mask_wport_sram = (0 until nWSel) map { i =>
      use_mask_lop(
        io.ticker.sram.write,
        (lop: ValidIO[SRAMRFWriteOp]) => lop.valid && lop.bits.selg && lop.bits.wsel === UInt(i)) }
    val use_mask_wport_pred =
      use_mask_lop(
        io.ticker.pred.write,
        (lop: ValidIO[PredRFWriteOp]) => lop.valid && lop.bits.selg)

    val select = Vec.fill(nSeq){new SeqSelect}

    val check =
      (0 until nSeq) map { r =>
        val op_idx = me(r).rports + UInt(expLatency, bRPorts+1)
        val strip = stripfn(e(r).vlen, Bool(false), me(r).fn)
        val ask_op_mask = UInt(strip_to_bmask(strip) << op_idx, maxXbarTicks+nBanks-1)
        val ask_wport_sram_mask = UInt(strip_to_bmask(strip) << me(r).wport.sram, maxWPortLatency+nBanks-1)
        val ask_wport_pred_mask = UInt(strip_to_bmask(strip) << me(r).wport.pred, maxPredWPortLatency+nBanks-1)
        def chk_shazard(use_mask: Bits, ask_mask: Bits) = (use_mask & ask_mask).orR
        def chk_op_shazard(use_mask: Bits) = chk_shazard(use_mask, ask_op_mask)
        def chk_rport(fn: RegFn, i: Int) =
          fn(me(r).base).valid && (
            fn(me(r).base).is_vector() && chk_op_shazard(use_mask_xbar(i)) ||
            fn(me(r).base).is_scalar() && chk_op_shazard(use_mask_sreg_global(i)))
        val chk_rport_0_1 = chk_rport(reg_vs1, 0) || chk_rport(reg_vs2, 1)
        val chk_rport_0_1_2 = chk_rport_0_1 || chk_rport(reg_vs3, 2)
        val chk_rport_2 = chk_rport(reg_vs1, 2)
        val chk_rport_3_4 = chk_rport(reg_vs1, 3) || chk_rport(reg_vs2, 4)
        val chk_rport_3_4_5 = chk_rport_3_4 || chk_rport(reg_vs3, 5)
        val chk_rport_5 = chk_rport(reg_vs1, 5)
        def chk_wport_sram(i: Int) =
          me(r).base.vd.valid && chk_shazard(use_mask_wport_sram(i), ask_wport_sram_mask)
        val chk_wport_sram_0 = chk_wport_sram(0)
        val chk_wport_sram_1 = chk_wport_sram(1)
        val chk_wport_pred =
          me(r).base.vd.valid && chk_shazard(use_mask_wport_pred, ask_wport_pred_mask)
        val shazard_vimu = chk_rport_0_1 || chk_wport_sram_0 || chk_op_shazard(use_mask_vimu)
        val shazard_vfmu0 = chk_rport_0_1_2 || chk_wport_sram_0 || chk_op_shazard(use_mask_vfmu(0))
        val shazard_vfmu1 = chk_rport_3_4_5 || chk_wport_sram_1 || chk_op_shazard(use_mask_vfmu(1))
        val shazard_vfcu = chk_rport_3_4 || chk_wport_sram_1 || chk_wport_pred || chk_op_shazard(use_mask_vfcu)
        val shazard_vfvu = chk_rport_2 || chk_wport_sram_0 || chk_op_shazard(use_mask_vfvu)
        val shazard_vgu = chk_rport_5 || chk_wport_sram_1 || chk_op_shazard(use_mask_vgu)
        val shazard_vqu = chk_rport_3_4 || chk_wport_sram_1 || chk_op_shazard(use_mask_vqu)
        select(r).vfmu := Mux(shazard_vfmu0, UInt(1), UInt(0))
        val a = me(r).active
        val out =
          a.vimu && shazard_vimu ||
          a.vfmu && shazard_vfmu0 && shazard_vfmu1 || a.vfcu && shazard_vfcu || a.vfvu && shazard_vfvu ||
          a.vgu && shazard_vgu || a.vqu && shazard_vqu
        out
      }

    def debug = {
      io.debug.shazard := check
      io.debug.use_mask_sreg_global := Vec((0 until nGOPL) map { i => use_mask_sreg_global(i) })
      io.debug.use_mask_xbar := Vec((0 until nGOPL) map { i => use_mask_xbar(i) })
      io.debug.use_mask_vimu := use_mask_vimu
      io.debug.use_mask_vfmu := Vec((0 until nVFMU) map { i => use_mask_vfmu(i) })
      io.debug.use_mask_vfcu := use_mask_vfcu
      io.debug.use_mask_vfvu := use_mask_vfvu
      io.debug.use_mask_vgu := use_mask_vgu
      io.debug.use_mask_vqu := use_mask_vqu
      io.debug.use_mask_wport_sram := use_mask_wport_sram
      io.debug.use_mask_wport_pred := use_mask_wport_pred
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // issue window helpers

  val iwindow = new {
    val next_update = Vec.fill(nSeq){Bool()}
    val next_v = Vec.fill(nSeq){Bool()}

    val set = new {
      def valid(n: UInt) = {
        next_update(n) := Bool(true)
        next_v(n) := Bool(true)
      }
    }

    val clear = new {
      def valid(n: UInt) = {
        next_update(n) := Bool(true)
        next_v(n) := Bool(false)
      }
    }

    def header = {
      (0 until nSeq) map { r =>
        next_update(r) := Bool(false)
        next_v(r) := v(r)
      }
    }

    def logic = {
      (0 until nSeq) map { r =>
        when (next_update(r)) {
          v(r) := next_v(r)
        }
        when (io.op.valid && io.master.update.valid(r)) {
          set.valid(UInt(r))
          e(r).reg := io.master.update.reg(r)
          e(r).vlen := io.op.bits.vlen
          e(r).eidx := UInt(0)
          e(r).age := UInt(0)
        }
        io.master.clear(r) := !v(r) || !next_v(r)
      }
    }

    def debug = {
      io.debug.valid := v
      io.debug.e := e
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // scheduling helpers

  val scheduling = new {
    def find_first(fn: Int=>Bool) = {
      val internal = Vec.fill(2*nSeq){Bool()}
      for (i <- 0 until nSeq) {
        internal(i+nSeq) := mv(i) && v(i) && fn(i)
        internal(i) := internal(i+nSeq) && (UInt(i) >= head)
      }
      val priority_oh = PriorityEncoderOH(internal)
      val out = Vec.fill(nSeq){Bool()}
      for (i <- 0 until nSeq) {
        out(i) := priority_oh(i) | priority_oh(i+nSeq)
      }
      out
    }

    def valfn(sched: Vec[Bool]) = sched.reduce(_||_)

    def mreadfn[T <: Data](sched: Vec[Bool], rfn: MasterSeqEntry=>T) =
      rfn(me(0)).clone.fromBits(Mux1H(sched, me.map(rfn(_).toBits)))

    def readfn[T <: Data](sched: Vec[Bool], rfn: SeqEntry=>T) =
      rfn(e(0)).clone.fromBits(Mux1H(sched, e.map(rfn(_).toBits)))

    def selectfn(sched: Vec[Bool]) =
      new SeqSelect().fromBits(Mux1H(sched, shazard.select.map(_.toBits)))

    def regfn(sched: Vec[Bool]) = {
      val out = new PhysicalRegisters
      List((preg_vp, reg_vp, pregid_vp),
           (preg_vs1, reg_vs1, pregid_vs1),
           (preg_vs2, reg_vs2, pregid_vs2),
           (preg_vs3, reg_vs3, pregid_vs3),
           (preg_vd, reg_vd, pregid_vd)) map {
        case (pfn, rfn, pidfn) =>
          pfn(out) := mreadfn(sched, (me: MasterSeqEntry) => rfn(me.base))
          pfn(out).id := readfn(sched, (e: SeqEntry) => pidfn(e.reg).id)
      }
      out
    }

    def nohazards(i: Int) =
      !dhazard.check(i) && !bhazard.check(i) && !shazard.check(i)

    val exp = new {
      val vgu_consider = Vec.fill(nSeq){Bool()}
      val vsu_consider = Vec.fill(nSeq){Bool()}
      val vqu_consider = Vec.fill(nSeq){Bool()}

      val consider = (i: Int) => nohazards(i) && (
        me(i).active.viu || me(i).active.vimu ||
        me(i).active.vfmu || me(i).active.vfcu || me(i).active.vfvu ||
        me(i).active.vgu && vgu_consider(i) ||
        me(i).active.vsu && vsu_consider(i) ||
        me(i).active.vqu && vqu_consider(i))
      val first_sched = find_first((i: Int) => consider(i) && e(i).age === UInt(0))
      val second_sched = find_first((i: Int) => consider(i))
      val sel = first_sched.reduce(_ || _)
      val sched = Vec(first_sched zip second_sched map { case (f, s) => Mux(sel, f, s) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val op = {
        val out = new SeqOp
        out.fn := mreadfn(sched, (me: MasterSeqEntry) => me.fn)
        out.reg := regfn(sched)
        out.sreg := mreadfn(sched, (me: MasterSeqEntry) => me.sreg)
        out.active := mreadfn(sched, (me: MasterSeqEntry) => me.active)
        out.select := selectfn(sched)
        out.eidx := readfn(sched, (e: SeqEntry) => e.eidx)
        out.rports := mreadfn(sched, (me: MasterSeqEntry) => me.rports)
        out.wport.sram := mreadfn(sched, (me: MasterSeqEntry) => me.wport.sram)
        out.wport.pred := mreadfn(sched, (me: MasterSeqEntry) => me.wport.pred)
        out.strip := stripfn(vlen, Bool(false), out.fn)
        out
      }

      def fire(n: Int) = sched(n)
      def fire_vgu = valid && op.active.vgu
      def fire_vsu = valid && op.active.vsu
      def fire_vqu = valid && op.active.vqu
      def fire_vqu_latch(n: Int) = fire_vqu && op.fn.vqu().latch(n)

      def logic = {
        io.seq.exp.valid := valid
        io.seq.exp.bits := op
      }

      def debug = {
        (io.debug.consider zipWithIndex) foreach { case (io, i) => io := consider(i) }
        (io.debug.first_sched zip first_sched) foreach { case (io, c) => io := c }
        (io.debug.second_sched zip second_sched) foreach { case (io, c) => io := c }
      }
    }

    val vipu = new {
      val consider = (i: Int) => me(i).active.vipu && nohazards(i)
      val first_sched = find_first((i: Int) => consider(i) && e(i).age === UInt(0))
      val second_sched = find_first((i: Int) => consider(i))
      val sel = first_sched.reduce(_ || _)
      val sched = Vec(first_sched zip second_sched map { case (f, s) => Mux(sel, f, s) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val op = {
        val out = new SeqVIPUOp
        out.fn := mreadfn(sched, (me: MasterSeqEntry) => me.fn)
        out.reg := regfn(sched)
        out.strip := stripfn(vlen, Bool(false), out.fn)
        out
      }

      def fire(n: Int) = sched(n)

      def logic = {
        io.seq.vipu.valid := valid
        io.seq.vipu.bits := op
      }
    }

    val vidu = new {
      val first = find_first((i: Int) => me(i).active.vidu)
      val sched = Vec((first zipWithIndex) map { case (f, i) => f && nohazards(i) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(sched, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)

      val ready = io.dila.available
      def fire(n: Int) = sched(n) && ready

      def logic = {
        io.dila.cnt := strip_to_bcnt(strip)
        io.dila.reserve := valid && ready
      }
    }

    val vfdu = new {
      val first = find_first((i: Int) => me(i).active.vfdu)
      val sched = Vec((first zipWithIndex) map { case (f, i) => f && nohazards(i) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(sched, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)

      val ready = io.dfla.available
      def fire(n: Int) = sched(n) && ready

      def logic = {
        io.dfla.cnt := strip_to_bcnt(strip)
        io.dfla.reserve := valid && ready
      }
    }

    val vpu = new {
      val first = find_first((i: Int) => me(i).active.vpu || me(i).active.vgu)
      val sched = Vec((first zipWithIndex) map { case (f, i) => f && nohazards(i) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(sched, (me: MasterSeqEntry) => me.fn)
      val op = {
        val out = new SeqVPUOp
        out.reg := regfn(sched)
        out.strip := stripfn(vlen, Bool(false), fn)
        out
      }

      val sel = mreadfn(sched, (me: MasterSeqEntry) => me.active.vgu)
      val ready = io.pla.available && (!sel || exp.fire_vgu)
      def fire(n: Int) = sched(n) && ready

      def logic = {
        io.seq.vpu.valid := valid && ready
        io.seq.vpu.bits := op
        io.pla.reserve := valid && ready
        io.pla.mask := strip_to_bmask(op.strip)
      }

      def debug = {
        (io.debug.pred_first zip sched) foreach { case (io, c) => io := c }
      }
    }

    val vgu = new {
      val first = find_first((i: Int) => me(i).active.vgu)
      val vlen = readfn(first, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(first, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)
      val cnt = strip_to_bcnt(strip)

      val ready = io.gpla.available && io.gqla.available
      (0 until nSeq) map { i =>
        exp.vgu_consider(i) := vpu.first(i) && first(i) && io.pla.available && ready }

      def logic = {
        io.gpla.cnt := cnt
        io.gpla.reserve := exp.fire_vgu
        io.gqla.cnt := cnt
        io.gqla.reserve := exp.fire_vgu
      }
    }

    val vcu = new {
      val first = find_first((i: Int) => me(i).active.vcu)
      val sched = Vec((first zipWithIndex) map { case (f, i) => f && nohazards(i) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(sched, (me: MasterSeqEntry) => me.fn)
      val mcmd = DecodedMemCommand(fn.vmu().cmd)
      val strip = stripfn(vlen, Bool(false), fn)

      val ready =
        io.vmu.pala.available &&
        (!mcmd.read || io.lreq.available) &&
        (!mcmd.write || io.sreq.available)
      def fire(n: Int) = sched(n) && ready

      def logic = {
        io.vmu.pala.cnt := strip
        io.vmu.pala.reserve := valid && ready
        io.lreq.cnt := strip
        io.lreq.reserve := valid && ready && mcmd.read
        io.sreq.cnt := strip
        io.sreq.reserve := valid && ready && mcmd.store
      }
    }

    val vlu = new {
      val first = find_first((i: Int) => me(i).active.vlu)
      val sched = Vec((first zipWithIndex) map { case (f, i) => f && nohazards(i) })
      val valid = valfn(sched)
      val vlen = readfn(sched, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(sched, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)

      val ready = io.lla.available
      def fire(n: Int) = sched(n) && ready

      def logic = {
        io.lla.cnt := strip
        io.lla.reserve := valid && io.lla.available
      }
    }

    val vsu = new {
      val first = find_first((i: Int) => me(i).active.vsu)
      val vlen = readfn(first, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(first, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)

      val ready = io.sla.available
      (0 until nSeq) map { i => exp.vsu_consider(i) := first(i) && ready }

      def logic = {
        io.sla.reserve := exp.fire_vsu
        io.sla.mask := strip_to_bmask(strip)
      }
    }

    val vqu = new {
      val first = find_first((i: Int) => me(i).active.vqu)
      val vlen = readfn(first, (e: SeqEntry) => e.vlen)
      val fn = mreadfn(first, (me: MasterSeqEntry) => me.fn)
      val strip = stripfn(vlen, Bool(false), fn)
      val cnt = strip_to_bcnt(strip)

      def ready(n: Int) =
        io.dpla.available &&
        (!me(n).fn.vqu().latch(0) || io.dqla(0).available) &&
        (!me(n).fn.vqu().latch(1) || io.dqla(1).available)
      (0 until nSeq) map { i => exp.vqu_consider(i) := first(i) && ready(i) }

      def logic = {
        io.dpla.cnt := cnt
        io.dpla.reserve := exp.fire_vqu
        (io.dqla zipWithIndex) map { case (la, i) =>
          la.cnt := cnt
          la.reserve := exp.fire_vqu_latch(i)
        }
      }
    }

    def logic = {
      exp.logic
      vipu.logic
      vidu.logic
      vfdu.logic
      vpu.logic
      vgu.logic
      vcu.logic
      vlu.logic
      vsu.logic
      vqu.logic

      def fire(n: Int) =
        exp.fire(n) || vipu.fire(n) ||
        vidu.fire(n) || vfdu.fire(n) ||
        vpu.fire(n) || vcu.fire(n) || vlu.fire(n)

      def update_reg(i: Int, fn: RegFn, pfn: PRegIdFn) = {
        when (fn(me(i).base).valid) {
          when (fn(me(i).base).is_vector()) {
            pfn(e(i).reg).id := pfn(e(i).reg).id + io.cfg.vstride
          }
          when (fn(me(i).base).is_pred()) {
            pfn(e(i).reg).id := pfn(e(i).reg).id + io.cfg.pstride
          }
        }
      }

      for (i <- 0 until nSeq) {
        val strip = stripfn(e(i).vlen, Bool(false), me(i).fn)
        assert (io.cfg.lstride === UInt(3), "need to fix sequencing logic otherwise")
        when (mv(i) && v(i)) {
          when (fire(i)) {
            e(i).vlen := e(i).vlen - strip
            e(i).eidx := e(i).eidx + (UInt(1) << io.cfg.lstride) * UInt(nLanes)
            update_reg(i, reg_vp, pregid_vp)
            update_reg(i, reg_vs1, pregid_vs1)
            update_reg(i, reg_vs2, pregid_vs2)
            update_reg(i, reg_vs3, pregid_vs3)
            update_reg(i, reg_vd, pregid_vd)
            when (e(i).vlen === strip) {
              iwindow.clear.valid(UInt(i))
            }
          }
          when (e(i).age.orR) {
            e(i).age := e(i).age - UInt(1)
          }
          when (exp.fire(i)) {
            e(i).age := UInt(nBanks-1)
          }
        }
      }
    }

    def debug = {
      vpu.debug
      exp.debug
    }
  }

  iwindow.header

  iwindow.logic
  scheduling.logic

  iwindow.debug
  dhazard.debug
  bhazard.debug
  shazard.debug
  scheduling.debug
}
