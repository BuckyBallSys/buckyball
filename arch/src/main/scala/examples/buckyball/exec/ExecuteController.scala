package buckyball.exec

import chisel3._
import chisel3.util._
import chisel3.stage._
import org.chipsalliance.cde.config.Parameters
import dialect.bbfp._
import dialect.vector._
import buckyball.frontend.rs.{ReservationStationIssue, ReservationStationComplete, BuckyBallCmd}
import buckyball.mem.{SramReadIO, SramWriteIO}
import buckyball.BuckyBallConfig

class ExecuteController(implicit b: BuckyBallConfig, p: Parameters) extends Module {
  val rob_id_width = log2Up(b.rob_entries)
  val spad_w = b.veclane * b.inputType.getWidth
  
  val io = IO(new Bundle {
    val cmdReq = Flipped(Decoupled(new ReservationStationIssue(new BuckyBallCmd, rob_id_width)))
    val cmdResp = Decoupled(new ReservationStationComplete(rob_id_width))
    
    // 连接到Scratchpad的SRAM读写接口
    val sramRead = Vec(b.sp_banks, Flipped(new SramReadIO(b.spad_bank_entries, spad_w)))
    val sramWrite = Vec(b.sp_banks, Flipped(new SramWriteIO(b.spad_bank_entries, spad_w, b.spad_mask_len)))
    // 连接到Accumulator的读写接口
    val accRead = Vec(b.acc_banks, Flipped(new SramReadIO(b.acc_bank_entries, b.acc_w)))
    val accWrite = Vec(b.acc_banks, Flipped(new SramWriteIO(b.acc_bank_entries, b.acc_w, b.acc_mask_len)))
  })

  val BBFP_Control = Module(new BBFP_Control)
  val VecUnit = Module(new VecUnit)

// -----------------------------------------------------------------------------
// Input Selector
// -----------------------------------------------------------------------------

  val sel               = WireInit(false.B)
  val sel_reg           = RegInit(false.B)

  val vec_unit :: bbfp_unit :: Nil = Enum(2)
  val real_sel = WireInit(vec_unit)

  when (io.cmdReq.valid) {
    sel := (!io.cmdReq.bits.cmd.post_decode_cmd.is_vec) && io.cmdReq.bits.cmd.post_decode_cmd.is_bbfp
  }

  when (io.cmdReq.fire) {
    sel_reg := sel
  }.otherwise {
    sel_reg := sel_reg
  }
  real_sel := Mux(io.cmdReq.valid, sel, sel_reg)

  io.cmdReq.ready := Mux(real_sel === vec_unit, BBFP_Control.io.cmdReq.ready, VecUnit.io.cmdReq.ready)

// -----------------------------------------------------------------------------
// 默认赋值, sb chisel 识别不出来两种情况全覆盖了，故手动赋值
// -----------------------------------------------------------------------------
  for (i <- 0 until b.sp_banks) {
    io.sramRead(i).req.valid        := false.B
    io.sramRead(i).req.bits.addr    := 0.U
    io.sramRead(i).req.bits.fromDMA := false.B
    io.sramRead(i).resp.ready       := false.B
    io.sramWrite(i).req.valid       := false.B
    io.sramWrite(i).req.bits.addr   := 0.U
    io.sramWrite(i).req.bits.data   := 0.U
    io.sramWrite(i).req.bits.mask   := VecInit(Seq.fill(b.spad_mask_len)(false.B))
  }
  for (i <- 0 until b.acc_banks) {
    io.accRead(i).req.valid         := false.B
    io.accRead(i).req.bits.addr     := 0.U
    io.accRead(i).req.bits.fromDMA  := false.B
    io.accRead(i).resp.ready        := false.B
    io.accWrite(i).req.valid        := false.B
    io.accWrite(i).req.bits.addr    := 0.U
    io.accWrite(i).req.bits.data    := 0.U
    io.accWrite(i).req.bits.mask    := VecInit(Seq.fill(b.acc_mask_len)(false.B))
  }
  BBFP_Control.io.sramRead.foreach(_.req.ready := false.B)
  BBFP_Control.io.sramRead.foreach(_.resp.valid := false.B)
  BBFP_Control.io.sramRead.foreach(_.resp.bits.data := 0.U)
  BBFP_Control.io.sramRead.foreach(_.resp.bits.fromDMA := false.B)
  BBFP_Control.io.sramWrite.foreach(_.req.ready := false.B)
  BBFP_Control.io.accRead.foreach(_.req.ready := false.B)
  BBFP_Control.io.accRead.foreach(_.resp.valid := false.B)
  BBFP_Control.io.accRead.foreach(_.resp.bits.data := 0.U)
  BBFP_Control.io.accRead.foreach(_.resp.bits.fromDMA := false.B)
  BBFP_Control.io.accWrite.foreach(_.req.ready := false.B)

  VecUnit.io.sramRead.foreach(_.req.ready := false.B)
  VecUnit.io.sramRead.foreach(_.resp.valid := false.B)
  VecUnit.io.sramRead.foreach(_.resp.bits.data := 0.U)
  VecUnit.io.sramRead.foreach(_.resp.bits.fromDMA := false.B)
  VecUnit.io.sramWrite.foreach(_.req.ready := false.B)
  VecUnit.io.accRead.foreach(_.req.ready := false.B)
  VecUnit.io.accRead.foreach(_.resp.valid := false.B)
  VecUnit.io.accRead.foreach(_.resp.bits.data := 0.U)
  VecUnit.io.accRead.foreach(_.resp.bits.fromDMA := false.B)
  VecUnit.io.accWrite.foreach(_.req.ready := false.B)

// -----------------------------------------------------------------------------
// BBFP_Control
// -----------------------------------------------------------------------------

  // cmdReq输入分发
  BBFP_Control.io.cmdReq.valid := io.cmdReq.valid && real_sel === bbfp_unit
  BBFP_Control.io.cmdReq.bits  := io.cmdReq.bits

  val real_is_matmul_ws = WireInit(false.B)
  val reg_is_matmul_ws  = RegInit(false.B)
  
  when (io.cmdReq.valid) {
    reg_is_matmul_ws := io.cmdReq.bits.cmd.post_decode_cmd.is_matmul_ws
  }
  real_is_matmul_ws := Mux(io.cmdReq.valid, io.cmdReq.bits.cmd.post_decode_cmd.is_matmul_ws, reg_is_matmul_ws)

  BBFP_Control.io.is_matmul_ws := real_is_matmul_ws
  

  // 连接到Scratchpad的SRAM读写接口
  when (real_sel === bbfp_unit) {
    for (i <- 0 until b.sp_banks) {
      // sramRead(i).req 
      io.sramRead(i).req.valid              := BBFP_Control.io.sramRead(i).req.valid
      io.sramRead(i).req.bits               := BBFP_Control.io.sramRead(i).req.bits
      BBFP_Control.io.sramRead(i).req.ready := io.sramRead(i).req.ready

      // sramRead(i).resp 
      BBFP_Control.io.sramRead(i).resp.valid := io.sramRead(i).resp.valid
      BBFP_Control.io.sramRead(i).resp.bits  := io.sramRead(i).resp.bits
      io.sramRead(i).resp.ready              := BBFP_Control.io.sramRead(i).resp.ready

      // sramWrite(i) 
      io.sramWrite(i).req.valid     := BBFP_Control.io.sramWrite(i).req.valid
      io.sramWrite(i).req.bits.addr := BBFP_Control.io.sramWrite(i).req.bits.addr
      io.sramWrite(i).req.bits.data := BBFP_Control.io.sramWrite(i).req.bits.data
      io.sramWrite(i).req.bits.mask := BBFP_Control.io.sramWrite(i).req.bits.mask
    }

    // 连接到Accumulator的读写接口
    for (i <- 0 until b.acc_banks) {
      // accRead(i).req 
      io.accRead(i).req.valid              := BBFP_Control.io.accRead(i).req.valid
      io.accRead(i).req.bits               := BBFP_Control.io.accRead(i).req.bits
      BBFP_Control.io.accRead(i).req.ready := io.accRead(i).req.ready

      // accRead(i).resp 
      BBFP_Control.io.accRead(i).resp.valid := io.accRead(i).resp.valid 
      BBFP_Control.io.accRead(i).resp.bits  := io.accRead(i).resp.bits
      io.accRead(i).resp.ready              := BBFP_Control.io.accRead(i).resp.ready

      // accWrite(i) 
      io.accWrite(i).req.valid     := BBFP_Control.io.accWrite(i).req.valid
      io.accWrite(i).req.bits.addr := BBFP_Control.io.accWrite(i).req.bits.addr
      io.accWrite(i).req.bits.data := BBFP_Control.io.accWrite(i).req.bits.data
      io.accWrite(i).req.bits.mask := BBFP_Control.io.accWrite(i).req.bits.mask
    }

  }




// -----------------------------------------------------------------------------
// VecUnit
// -----------------------------------------------------------------------------

  VecUnit.io.cmdReq.valid      := io.cmdReq.valid && real_sel === vec_unit
  VecUnit.io.cmdReq.bits       := io.cmdReq.bits


    // 连接到Scratchpad的SRAM读写接口
  when (real_sel === vec_unit) {
    for (i <- 0 until b.sp_banks) {
      // sramRead(i).req
      io.sramRead(i).req.valid         := VecUnit.io.sramRead(i).req.valid
      io.sramRead(i).req.bits          := VecUnit.io.sramRead(i).req.bits
      VecUnit.io.sramRead(i).req.ready := io.sramRead(i).req.ready

      // sramRead(i).resp
      VecUnit.io.sramRead(i).resp.valid      := io.sramRead(i).resp.valid
      VecUnit.io.sramRead(i).resp.bits       := io.sramRead(i).resp.bits
      io.sramRead(i).resp.ready              := VecUnit.io.sramRead(i).resp.ready

      // sramWrite(i)
      io.sramWrite(i).req.valid     := VecUnit.io.sramWrite(i).req.valid
      io.sramWrite(i).req.bits.addr := VecUnit.io.sramWrite(i).req.bits.addr
      io.sramWrite(i).req.bits.data := VecUnit.io.sramWrite(i).req.bits.data
      io.sramWrite(i).req.bits.mask := VecUnit.io.sramWrite(i).req.bits.mask
    }

    // 连接到Accumulator的读写接口
    for (i <- 0 until b.acc_banks) {
      // accRead(i).req
      io.accRead(i).req.valid              := VecUnit.io.accRead(i).req.valid
      io.accRead(i).req.bits               := VecUnit.io.accRead(i).req.bits
      VecUnit.io.accRead(i).req.ready      := io.accRead(i).req.ready

      // accRead(i).resp
      VecUnit.io.accRead(i).resp.valid      := io.accRead(i).resp.valid
      VecUnit.io.accRead(i).resp.bits       := io.accRead(i).resp.bits
      io.accRead(i).resp.ready              := VecUnit.io.accRead(i).resp.ready

      // accWrite(i)
      io.accWrite(i).req.valid     := VecUnit.io.accWrite(i).req.valid
      io.accWrite(i).req.bits.addr := VecUnit.io.accWrite(i).req.bits.addr
      io.accWrite(i).req.bits.data := VecUnit.io.accWrite(i).req.bits.data
      io.accWrite(i).req.bits.mask := VecUnit.io.accWrite(i).req.bits.mask
    }
  }

// -----------------------------------------------------------------------------
// Output Selector
// -----------------------------------------------------------------------------
  // cmdResp输出分发
  io.cmdResp.valid := Mux(real_sel === bbfp_unit, BBFP_Control.io.cmdResp.valid, VecUnit.io.cmdResp.valid)// only valid need
  io.cmdResp.bits  := Mux(real_sel === bbfp_unit, BBFP_Control.io.cmdResp.bits, VecUnit.io.cmdResp.bits)
  BBFP_Control.io.cmdResp.ready := io.cmdResp.ready && real_sel === bbfp_unit
  VecUnit.io.cmdResp.ready      := io.cmdResp.ready && real_sel === vec_unit
}