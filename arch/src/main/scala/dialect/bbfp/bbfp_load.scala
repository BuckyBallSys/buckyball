package dialect.bbfp

import chisel3._
import chisel3.util._
import chisel3.stage._
import org.chipsalliance.cde.config.Parameters

import dialect.bbfp._
import buckyball.frontend.rs.{ReservationStationIssue, ReservationStationComplete, BuckyBallCmd}
import buckyball.mem.{SramReadIO, SramWriteIO, SramReadReq}
import buckyball.BuckyBallConfig

class BBFP_LoadUnit(implicit bbconfig: BuckyBallConfig, p: Parameters) extends Module {
    val rob_id_width = log2Up(bbconfig.rob_entries)
    val spad_w = bbconfig.veclane * bbconfig.inputType.getWidth
    val io = IO(new Bundle {
        val sramReadReq = Vec(bbconfig.sp_banks,Decoupled(new SramReadReq(bbconfig.spad_bank_entries)))
        val id_lu_i = Flipped(Decoupled(new id_lu_req))
        val lu_ex_o = Decoupled(new lu_ex_req)
  })

  val op1_bank = io.id_lu_i.bits.op1_bank
  val op1_bank_addr = io.id_lu_i.bits.op1_bank_addr
  val op2_bank = io.id_lu_i.bits.op2_bank
  val op2_bank_addr = io.id_lu_i.bits.op2_bank_addr
  val wr_bank = io.id_lu_i.bits.wr_bank
  val wr_bank_addr = io.id_lu_i.bits.wr_bank_addr
  
  //每个bank读请求默认赋值
  for(i <- 0 until bbconfig.sp_banks){
      io.sramReadReq(i).valid := false.B
      io.sramReadReq(i).bits.fromDMA := false.B
      io.sramReadReq(i).bits.addr := 0.U
  }

  //根据ID_LU的输入生成SRAM读请求
  when(io.id_lu_i.valid){
      io.sramReadReq(op1_bank).valid := true.B
      io.sramReadReq(op1_bank).bits.fromDMA := false.B
      io.sramReadReq(op1_bank).bits.addr := op1_bank_addr

      io.sramReadReq(op2_bank).valid := true.B
      io.sramReadReq(op2_bank).bits.fromDMA := false.B
      io.sramReadReq(op2_bank).bits.addr := op2_bank_addr
  }

  //生成LU_EX请求
  io.lu_ex_o.valid := io.id_lu_i.valid
  io.lu_ex_o.bits.op1_bank := op1_bank
  io.lu_ex_o.bits.op2_bank := op2_bank
  io.lu_ex_o.bits.wr_bank := wr_bank
  io.lu_ex_o.bits.wr_bank_addr := wr_bank_addr
  io.lu_ex_o.bits.opcode := io.id_lu_i.bits.opcode
  io.lu_ex_o.bits.iter := io.id_lu_i.bits.iter
  io.lu_ex_o.bits.thread_id := io.id_lu_i.bits.thread_id

  io.id_lu_i.ready := io.lu_ex_o.ready

    
}