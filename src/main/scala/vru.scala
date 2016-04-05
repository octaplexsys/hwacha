package hwacha

import Chisel._
import cde.Parameters

/*
 * TODO:
 * - when the decoded mem op queue overflows, should we discard what's at the
 * head or at the tail?
 * - counter overflows?
 * - vmcs assert
 */

object VRUDecodeTable {
  import HwachaElementInstructions._

  /* list contains: 
   * opwidth (2 bits, stored as 2^opwidth)
   * store (false for load, true for store)
   * prefetchable (0 for VRU ignore, 1 for VRU use)
   * STOP (end of vf block)
   */
  val default: List[BitPat] = List(UInt(0), N, N, N)

  val table: Array[(BitPat, List[BitPat])] = Array(
    VSTOP -> List(X, X, N, Y),
    VLD   -> List(UInt(3), N, Y, N),
    VSD   -> List(UInt(3), Y, Y, N),
    VLW   -> List(UInt(2), N, Y, N),
    VLWU  -> List(UInt(2), N, Y, N),
    VSW   -> List(UInt(2), Y, Y, N),
    VLH   -> List(UInt(1), N, Y, N),
    VLHU  -> List(UInt(1), N, Y, N),
    VSH   -> List(UInt(1), Y, Y, N),
    VLB   -> List(UInt(0), N, Y, N),
    VLBU  -> List(UInt(0), N, Y, N),
    VSB   -> List(UInt(0), Y, Y, N)
  )
}

class DecodedMemOp(implicit p: Parameters) extends HwachaBundle()(p) {
  // 64 is hardcoded in scalar-unit
  val addr = UInt(width = 64)
  val curr_vlen = UInt(width = bMLVLen)
  val opwidth = UInt(width=2) // byte = 0, half = 1, word = 2, double = 3
  val ls = UInt(width = 1) // load = 0, store = 1
}

/*
 * Formerly called ThrottleManager
 *
 * This module regulates the amount that the VRU can runahead by stalling 
 * the decoding of VF blocks.
 *
 * It also measures the number of vf blocks that we are ahead/behind to 
 * facilitate skipping vf blocks to purposefully get ahead
 *
 * To deal with predication, runahead is always tracked at VF block granularity.
 * The runahead counter is always brought back into sync at the end of each 
 * vf block, even with predication.
 */
class RunaheadManager(resetSignal: Bool = null)(implicit p: Parameters) extends HwachaModule(_reset = resetSignal)(p) {
  val entrywidth = 20 // width of per-vf-block bytes loaded/stored counter
  val throttleQueueDepth = 10

  val io = new Bundle {
    // runahead distance throttling
    val enq = Decoupled(UInt(width=entrywidth)).flip
    val vf_done_vxu = Bool(INPUT)
    val stall_prefetch = Bool(OUTPUT)

    // vf block skipping
    val vf_fire = Bool(INPUT)
    val vf_skip = Bool(OUTPUT)
  }

  val skipamt = p(HwachaVRUEarlyIgnore)
  val runahead_vf_count = Reg(init = SInt(0, width=32))

  io.vf_skip := runahead_vf_count < SInt(skipamt)

  // number of bytes the VRU can runahead
  val MAX_RUNAHEAD = p(HwachaVRUMaxRunaheadBytes)

  val shim = Decoupled(UInt(width=entrywidth)).asDirectionless()

  // this queue tracks the number of bytes loaded/stored per vf block
  // in flight (where in-flight = sent to prefetch stage, but not acked by 
  // vxu)
  val bytes_per_vf_queue = Queue(shim, throttleQueueDepth)

  // the number of bytes the prefetcher is ahead of the vxu
  val runahead_bytes_count = Reg(init=UInt(0, width=32))

  // signal to the decode stage that we should stop decoding VF blocks because
  // we have run too far ahead
  io.stall_prefetch := runahead_bytes_count > UInt(MAX_RUNAHEAD)

  // queue does not need to worry about ignoring entries when we fall behind,
  // because the vf blocks will never enter decode
  shim.valid := io.enq.valid && !(io.vf_done_vxu && !bytes_per_vf_queue.valid)
  io.enq.ready := shim.ready
  shim.bits := io.enq.bits

  // only one of these can be true at a time
  val skipped_block = io.vf_fire && io.vf_skip
  val accepted_block = io.enq.fire()
  assert(!(skipped_block && accepted_block), "VRU attempted to simultaneously enqueue and skip VF block")
  val increment_vf_count = skipped_block || accepted_block

  runahead_vf_count := runahead_vf_count + UInt(increment_vf_count) - UInt(io.vf_done_vxu)

  val increment_bytes_necessary = shim.valid && shim.ready
  val decrement_bytes_necessary = bytes_per_vf_queue.valid && io.vf_done_vxu

  bytes_per_vf_queue.ready := io.vf_done_vxu

  val next_increment_bytes_value = Mux(increment_bytes_necessary, shim.bits, UInt(0))
  val next_decrement_bytes_value = Mux(decrement_bytes_necessary, bytes_per_vf_queue.bits, UInt(0))
  runahead_bytes_count := runahead_bytes_count + next_increment_bytes_value - next_decrement_bytes_value
}

/*
 * The PrefetchUnit is actually responsible for sending out prefetches from
 * the decodedMemOpQueue.
 *
 * The only throttling applied here is based on the allowed number of
 * outstanding requests
 */
class PrefetchUnit(resetSignal: Bool = null)(implicit p: Parameters) extends HwachaModule(_reset = resetSignal)(p) 
  with MemParameters {
  import uncore._

  val io = new Bundle {
    val memop = Decoupled(new DecodedMemOp).flip
    val dmem = new ClientUncachedTileLinkIO
  }

  val tag_count = Reg(init = UInt(0, tlClientXactIdBits))

  val tlBlockAddrOffset = tlBeatAddrBits + tlByteAddrBits
  val req_addr = io.memop.bits.addr(bPAddr-1, tlBlockAddrOffset)
  val req_vlen = io.memop.bits.curr_vlen // vector len
  val req_opwidth = io.memop.bits.opwidth // byte = 0 ... double = 3
  val req_ls = io.memop.bits.ls // load = 0, store = 1
  val prefetch_ip = Reg(init=Bool(false))

  val vec_len_bytes = req_vlen << req_opwidth
  val num_blocks_pf = vec_len_bytes >> tlBlockAddrOffset

  // TODO: calculate width
  val pf_ip_counter = Reg(init = UInt(0, width=20))

  // TODO: log2Up for width?
  val MAX_OUTSTANDING_PREFETCHES = UInt(p(HwachaVRUMaxOutstandingPrefetches), width=10) // approximately 1/3 of the units
  val remaining_allowed_prefetches = Reg(init=MAX_OUTSTANDING_PREFETCHES)

  assert(remaining_allowed_prefetches <= MAX_OUTSTANDING_PREFETCHES, "VRU: THROTTLE TOO LARGE\n")

  io.dmem.acquire.bits := Mux(req_ls === UInt(0), 
    GetPrefetch(tag_count, req_addr+pf_ip_counter), 
    PutPrefetch(tag_count, req_addr+pf_ip_counter))

  io.dmem.acquire.valid := Bool(false)
  io.memop.ready := Bool(false)

  when (io.memop.valid && !prefetch_ip && io.dmem.acquire.ready) {
    prefetch_ip := Bool(true)
    pf_ip_counter := UInt(0)
  }

  val movecond1 = prefetch_ip && 
    pf_ip_counter < (num_blocks_pf-UInt(1)) && io.dmem.acquire.ready && 
    remaining_allowed_prefetches > UInt(0)


  when (movecond1) {
    pf_ip_counter := pf_ip_counter + UInt(1)
    tag_count := tag_count + UInt(1)
    when (!io.dmem.grant.valid) {
      remaining_allowed_prefetches := remaining_allowed_prefetches - UInt(1)
    }
  }

  val movecond2 = prefetch_ip && 
    pf_ip_counter === (num_blocks_pf - UInt(1)) && io.dmem.acquire.ready && 
    remaining_allowed_prefetches > UInt(0)

  when (movecond2) {
    pf_ip_counter := UInt(0)
    prefetch_ip := Bool(false)
    tag_count := tag_count + UInt(1)
    io.memop.ready := Bool(true)
    when (!io.dmem.grant.valid) {
      remaining_allowed_prefetches := remaining_allowed_prefetches - UInt(1)
    }
  }

  io.dmem.acquire.valid := prefetch_ip && remaining_allowed_prefetches > UInt(0)
  io.dmem.grant.ready := Bool(true)
  when (io.dmem.grant.valid) {
    when (!(movecond1 || movecond2)) {
      remaining_allowed_prefetches := remaining_allowed_prefetches + UInt(1)
    }
  }
}


/* 
 * This module is notified when a vf command is received, then:
 *
 * 1) gets the instructions in the VF block from the icache
 *
 * 2) determines the total number of bytes loaded/stored in the VF block 
 * (repeat loads/stores to the same address will be double counted)
 *
 * 3) As it sees loads/stores, puts them into the decoded memop queue for use
 * by the PrefetchUnit. There is no backpressure here, we drop entries if
 * we don't have space to remember them
 *
 * 4) If the runahead manager determines that we aren't too far ahead, it 
 * enqueues tracking information in the runahead manager's tracking queue,
 * otherwise, we halt vf decode (and thus stop accepting any more rocc commands)
 * until the runahead manager determines that it is okay to continue
 *
 */
class VRUFrontend(resetSignal: Bool = null)(implicit p: Parameters) extends HwachaModule(_reset = resetSignal)(p) {

  val io = new Bundle {
    val imem = new FrontendIO

    val fire_vf = Bool(INPUT)
    val fetch_pc = UInt(INPUT)
    val vf_active = Bool(OUTPUT)
    val vf_complete_ack = Bool(INPUT)

    val memop = Decoupled(new DecodedMemOp)
    val vlen = UInt(INPUT)

    val aaddr = UInt(OUTPUT)
    val adata = UInt(INPUT)
  }

  val vf_active = Reg(init=Bool(false)) 
  io.vf_active := vf_active

  val runaheadman = Module(new RunaheadManager)
  runaheadman.io.vf_done_vxu := io.vf_complete_ack
  runaheadman.io.vf_fire := io.fire_vf

  /* Process the VF block if the runaheadmanager has not told us to skip it
   * For example at the start of a program or if there has been no prefetching
   * for a while and the prefetcher is all caught up
   */
  when (io.fire_vf && !runaheadman.io.vf_skip) {
    vf_active := Bool(true)
  }

 /* pause if we get to a VSTOP but the runaheadman is not ready to accept 
  * new entries in the vf block load/store byte tracking queue or if 
  * the throttle manager says to throttle

  * it is okay to stop instruction decode when stop_at_VSTOP is true without
  * causing a lockup because either:

  * 1) the runaheadman queue is full, which means the vxu is behind anyway
  * 2) the vru is very far ahead in terms of runahead distance, which means 
  * vxu is behind
  */
  val stop_at_VSTOP = !runaheadman.io.enq.ready || runaheadman.io.stall_prefetch

  // do a fetch 
  io.imem.req.valid := io.fire_vf && !runaheadman.io.vf_skip //fixed
  io.imem.req.bits.pc := io.fetch_pc
  io.imem.active := vf_active
  io.imem.invalidate := Bool(false)

  val loaded_inst = io.imem.resp.bits.data(0)
  io.aaddr := loaded_inst(28, 24)
  io.memop.bits.addr := io.adata
  io.memop.bits.curr_vlen := io.vlen

  // decode logic from table
  val logic = rocket.DecodeLogic(loaded_inst, VRUDecodeTable.default, VRUDecodeTable.table)
  val cs = logic.map {
    case b if b.inputs.head.getClass == classOf[Bool] => b.toBool
    case u => u
  }

  val (opwidth: UInt) :: (ls: Bool) :: (prefetchable: Bool) :: (stop: Bool) :: Nil = cs

  val imem_use_resp = io.imem.resp.valid && vf_active

  // as long as we're not stopped at a VSTOP (see above), accept instruction
  io.imem.resp.ready := !stop || (stop && !stop_at_VSTOP)

  io.memop.bits.opwidth := opwidth
  io.memop.bits.ls := ls
  io.memop.valid := prefetchable && imem_use_resp

  val current_ls_bytes = Reg(init=UInt(0, width=runaheadman.entrywidth))
  current_ls_bytes := Mux(prefetchable && imem_use_resp, 
    current_ls_bytes + (io.vlen << opwidth), 
    Mux(stop && !stop_at_VSTOP && imem_use_resp, UInt(0), current_ls_bytes)
  )

  runaheadman.io.enq.bits := current_ls_bytes
  runaheadman.io.enq.valid := stop && !runaheadman.io.stall_prefetch && imem_use_resp

  when (stop && !stop_at_VSTOP && imem_use_resp) {
      vf_active := Bool(false)
  }
}


class VRURoCCUnit(implicit p: Parameters) extends HwachaModule()(p) {
  import Commands._

  val io = new Bundle {
    val cmdq = new CMDQIO().flip
    val fire_vf = Bool(OUTPUT)
    val vf_active = Bool(INPUT)
    val vlen = UInt(OUTPUT)
    val aaddr = UInt(INPUT)
    val adata = UInt(OUTPUT)
  }

  // addr regfile
  val arf = Mem(UInt(width = 64), 32)
  val vlen = Reg(init=UInt(0, bMLVLen))
  io.vlen := vlen
  io.adata := arf(io.aaddr)

  val decode_vmca    = io.cmdq.cmd.bits === CMD_VMCA
  val decode_vsetcfg = io.cmdq.cmd.bits === CMD_VSETCFG
  val decode_vsetvl  = io.cmdq.cmd.bits === CMD_VSETVL
  val decode_vf      = io.cmdq.cmd.bits === CMD_VF
  val decode_vft     = io.cmdq.cmd.bits === CMD_VFT

  val deq_imm = decode_vmca || decode_vf || decode_vft || decode_vsetvl || decode_vsetcfg
  val deq_rd  = decode_vmca

  val mask_imm_valid = !deq_imm || io.cmdq.imm.valid
  val mask_rd_valid  = !deq_rd  || io.cmdq.rd.valid

  def fire_cmdq(exclude: Bool, include: Bool*) = {
    val rvs = Seq(
      !io.vf_active,
      io.cmdq.cmd.valid,
      mask_imm_valid,
      mask_rd_valid
    )
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }

  io.fire_vf := fire_cmdq(null, decode_vf)

  io.cmdq.cmd.ready := fire_cmdq(io.cmdq.cmd.valid)
  io.cmdq.imm.ready := fire_cmdq(mask_imm_valid, deq_imm)
  io.cmdq.rd.ready := fire_cmdq(mask_rd_valid, deq_rd)

  when (fire_cmdq(null, decode_vmca)) {
    arf(io.cmdq.rd.bits) := io.cmdq.imm.bits
  }

  when (fire_cmdq(null, decode_vsetcfg)) {
    vlen := UInt(0)
  }

  when (fire_cmdq(null, decode_vsetvl)) {
    vlen := io.cmdq.imm.bits
  }

}


class VRU(implicit p: Parameters) extends HwachaModule()(p)
  with MemParameters {
  import Commands._
  import uncore._

  val io = new Bundle {
    // to is implicit, -> imem
    val imem = new FrontendIO
    val cmdq = new CMDQIO().flip 
    val dmem = new ClientUncachedTileLinkIO
    // shorten names
    val vf_complete_ack = Bool(INPUT)
  }

  val vru_frontend = Module(new VRUFrontend)
  val vru_rocc_unit = Module(new VRURoCCUnit)

  // queue of load/store/addr/len to L2 prefetching stage
  val decodedMemOpQueue = Module(new Queue(new DecodedMemOp, 10))

  // wire up vru_rocc_unit, vec inst decode unit
  vru_rocc_unit.io.cmdq <> io.cmdq
  vru_rocc_unit.io.aaddr := vru_frontend.io.aaddr
  vru_rocc_unit.io.vf_active := vru_frontend.io.vf_active
  vru_frontend.io.imem <> io.imem
  vru_frontend.io.fire_vf := vru_rocc_unit.io.fire_vf
  vru_frontend.io.fetch_pc := io.cmdq.imm.bits
  vru_frontend.io.memop <> decodedMemOpQueue.io.enq
  vru_frontend.io.vf_complete_ack := io.vf_complete_ack
  vru_frontend.io.vlen := vru_rocc_unit.io.vlen
  vru_frontend.io.adata := vru_rocc_unit.io.adata

  // prefetch unit
  val prefetch_unit = Module(new PrefetchUnit)
  prefetch_unit.io.memop <> decodedMemOpQueue.io.deq
  io.dmem <> prefetch_unit.io.dmem
}
