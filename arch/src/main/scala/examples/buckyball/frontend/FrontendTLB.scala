package buckyball.frontend

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.{CoreBundle, CoreModule}
import freechips.rocketchip.tilelink.TLEdgeOut

import buckyball.util.Util._

class DecoupledTLBReq(val lgMaxSize: Int)(implicit p: Parameters) extends CoreBundle {
  val tlb_req = new TLBReq(lgMaxSize)
  val status = new MStatus
}

class TLBExceptionIO extends Bundle {
  val interrupt = Output(Bool())
  val flush_retry = Input(Bool())
  val flush_skip = Input(Bool())

  def flush(dummy: Int = 0): Bool = flush_retry || flush_skip
}

// TODO can we make TLB hits only take one cycle?
class DecoupledTLB(entries: Int, maxSize: Int)(implicit edge: TLEdgeOut, p: Parameters)
  extends CoreModule {

  val lgMaxSize = log2Ceil(maxSize)
  val io = IO(new Bundle {
    val req = Flipped(Valid(new DecoupledTLBReq(lgMaxSize)))
    val resp = new TLBResp
    val ptw = new TLBPTWIO
    val exp = new TLBExceptionIO
  })

  val interrupt = RegInit(false.B)
  io.exp.interrupt := interrupt

  val tlb = Module(new TLB(false, lgMaxSize, TLBConfig(nSets=1, nWays=entries)))
  tlb.io.req.valid := io.req.valid
  tlb.io.req.bits := io.req.bits.tlb_req
  io.resp := tlb.io.resp
  tlb.io.kill := false.B

  tlb.io.sfence.valid := io.exp.flush()
  tlb.io.sfence.bits.rs1 := false.B
  tlb.io.sfence.bits.rs2 := false.B
  tlb.io.sfence.bits.addr := DontCare
  tlb.io.sfence.bits.asid := DontCare
  tlb.io.sfence.bits.hv := false.B
  tlb.io.sfence.bits.hg := false.B

  io.ptw <> tlb.io.ptw
  tlb.io.ptw.status := io.req.bits.status
  val exception = io.req.valid && Mux(io.req.bits.tlb_req.cmd === M_XRD, tlb.io.resp.pf.ld || tlb.io.resp.ae.ld, tlb.io.resp.pf.st || tlb.io.resp.ae.st)
  when (exception) { interrupt := true.B }
  when (interrupt && tlb.io.sfence.fire) {
    interrupt := false.B
  }

  assert(!io.exp.flush_retry || !io.exp.flush_skip, "TLB: flushing with both retry and skip at same time")
}

class FrontendTLBIO(implicit p: Parameters) extends CoreBundle {
  val lgMaxSize = log2Ceil(coreDataBytes)
  val req = Valid(new DecoupledTLBReq(lgMaxSize))
  val resp = Flipped(new TLBResp)
}

class FrontendTLB(nClients: Int, entries: Int, maxSize: Int)
                 (implicit edge: TLEdgeOut, p: Parameters) extends CoreModule {

  val lgMaxSize = log2Ceil(coreDataBytes)

  val io = IO(new Bundle {
    val clients = Flipped(Vec(nClients, new FrontendTLBIO))
    val ptw = Vec(nClients, new TLBPTWIO)
    val exp = Vec(nClients, new TLBExceptionIO)
  })

  val tlbs = Seq.fill(nClients)(Module(new DecoupledTLB(entries, maxSize)))

  io.ptw <> VecInit(tlbs.map(_.io.ptw))
  io.exp <> VecInit(tlbs.map(_.io.exp))

  io.clients.zipWithIndex.foreach { case (client, i) =>
    val last_translated_valid = RegInit(false.B)
    val last_translated_vpn = RegInit(0.U(vaddrBits.W))
    val last_translated_ppn = RegInit(0.U(paddrBits.W))

    val l0_tlb_hit = last_translated_valid && ((client.req.bits.tlb_req.vaddr >> pgIdxBits).asUInt === (last_translated_vpn >> pgIdxBits).asUInt)
    val l0_tlb_paddr = Cat(last_translated_ppn >> pgIdxBits, client.req.bits.tlb_req.vaddr(pgIdxBits-1,0))

    val tlb = tlbs(i)
    val tlbReq = tlb.io.req.bits
    val tlbReqValid = tlb.io.req.valid
    val tlbReqFire = tlb.io.req.fire

    val l0_tlb_paddr_reg = RegEnable(client.req.bits.tlb_req.vaddr, client.req.valid)
    
    tlbReqValid := RegNext(client.req.valid && !l0_tlb_hit)
    tlbReq := RegNext(client.req.bits)

    when (tlbReqFire && !tlb.io.resp.miss) {
      last_translated_valid := true.B
      last_translated_vpn := tlbReq.tlb_req.vaddr
      last_translated_ppn := tlb.io.resp.paddr
    }

    when (tlb.io.exp.flush()) {
      last_translated_valid := false.B
    }

    when (tlbReqFire) {
      client.resp := tlb.io.resp
    }.otherwise {
      client.resp := DontCare
      client.resp.paddr :=  l0_tlb_paddr_reg
      client.resp.miss := !RegNext(l0_tlb_hit)
    }
  }
}
