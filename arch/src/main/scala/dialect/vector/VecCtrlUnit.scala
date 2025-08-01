package dialect.vector

import chisel3._
import chisel3.util._
import chisel3.stage._
import org.chipsalliance.cde.config.Parameters

import dialect.vector._
import buckyball.frontend.rs.{ReservationStationIssue, ReservationStationComplete, BuckyBallCmd}
import buckyball.mem.{SramReadIO, SramWriteIO}
import buckyball.BuckyBallConfig

class VecCtrlUnit(implicit b: BuckyBallConfig, p: Parameters) extends Module {
  val rob_id_width = log2Up(b.rob_entries)

  val io = IO(new Bundle{
    val cmdReq = Flipped(Decoupled(new ReservationStationIssue(new BuckyBallCmd, rob_id_width)))
    val cmdResp_o = Decoupled(new ReservationStationComplete(rob_id_width))
    
    val ctrl_ld_o = Decoupled(new ctrl_ld_req)
    val ctrl_st_o = Decoupled(new ctrl_st_req)
    val ctrl_ex_o = Decoupled(new ctrl_ex_req)
    
    val cmdResp_i = Flipped(Valid(new Bundle {val commit = Bool()})) // from store unit
  })
  
  val rob_id_reg    = RegInit(0.U(rob_id_width.W))
  val iter          = RegInit(0.U(10.W))
  val op1_bank      = RegInit(0.U(2.W))
  val op1_bank_addr = RegInit(0.U(12.W))
  val op2_bank_addr = RegInit(0.U(12.W))
  val op2_bank      = RegInit(0.U(2.W))
  val wr_bank       = RegInit(0.U(2.W))
  val wr_bank_addr  = RegInit(0.U(12.W))
  val is_acc        = RegInit(false.B) 
  val has_send      = RegInit(false.B)

  val idle :: busy :: Nil = Enum(2)
  val state = RegInit(idle)

// -----------------------------------------------------------------------------
// EX指令到来设置寄存器
// -----------------------------------------------------------------------------

  when(io.cmdReq.fire) {
    iter          := io.cmdReq.bits.cmd.post_decode_cmd.iter
    rob_id_reg    := io.cmdReq.bits.rob_id
    op1_bank      := io.cmdReq.bits.cmd.post_decode_cmd.op1_bank
    op1_bank_addr := io.cmdReq.bits.cmd.post_decode_cmd.op1_bank_addr
    op2_bank      := io.cmdReq.bits.cmd.post_decode_cmd.op2_bank
    op2_bank_addr := io.cmdReq.bits.cmd.post_decode_cmd.op2_bank_addr
    wr_bank       := io.cmdReq.bits.cmd.post_decode_cmd.wr_bank
    wr_bank_addr  := io.cmdReq.bits.cmd.post_decode_cmd.wr_bank_addr
    is_acc        := io.cmdReq.bits.cmd.post_decode_cmd.is_acc
    
    state         := busy
  }

// -----------------------------------------------------------------------------
// 发送控制信号到VecUnit的load/store/ex单元
// -----------------------------------------------------------------------------

  when(state === busy && !has_send) {
    io.ctrl_ld_o.valid               := true.B
    io.ctrl_ld_o.bits.op1_bank       := op1_bank
    io.ctrl_ld_o.bits.op1_bank_addr  := op1_bank_addr
    io.ctrl_ld_o.bits.op2_bank       := op2_bank
    io.ctrl_ld_o.bits.op2_bank_addr  := op2_bank_addr
    io.ctrl_ld_o.bits.iter           := iter

    io.ctrl_ex_o.valid               := true.B
    io.ctrl_ex_o.bits.iter           := iter

    io.ctrl_st_o.valid               := true.B
    io.ctrl_st_o.bits.wr_bank        := wr_bank
    io.ctrl_st_o.bits.wr_bank_addr   := wr_bank_addr
    io.ctrl_st_o.bits.iter           := iter

    has_send                         := true.B
  }.otherwise {
    io.ctrl_ld_o.valid               := false.B
    io.ctrl_ld_o.bits.op1_bank       := 0.U
    io.ctrl_ld_o.bits.op1_bank_addr  := 0.U
    io.ctrl_ld_o.bits.op2_bank       := 0.U
    io.ctrl_ld_o.bits.op2_bank_addr  := 0.U
    io.ctrl_ld_o.bits.iter           := 0.U

    io.ctrl_ex_o.valid               := false.B
    io.ctrl_ex_o.bits.iter           := 0.U

    io.ctrl_st_o.valid               := false.B
    io.ctrl_st_o.bits.wr_bank        := 0.U
    io.ctrl_st_o.bits.wr_bank_addr   := 0.U
    io.ctrl_st_o.bits.iter           := 0.U
  }

// -----------------------------------------------------------------------------
// 等待VecUnit的最后的写回完成
// -----------------------------------------------------------------------------

  when(io.cmdResp_i.valid) {
    io.cmdResp_o.valid       := true.B
    io.cmdResp_o.bits.rob_id := rob_id_reg
    state                    := idle
    has_send                 := false.B
  }.otherwise {
    io.cmdResp_o.valid       := false.B
    io.cmdResp_o.bits.rob_id := 0.U
  }

  io.cmdReq.ready := state === idle

}

