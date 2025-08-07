#include "buckyball.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>


void init_matrix(elem_t* matrix, int rows, int cols, int seed) {
    srand(seed);
    for (int i = 0; i < rows * cols; i++) {
        matrix[i] = rand() % 4;  // Initialize with random values in the range [0, 127]
    }
}
void flip_matrix(elem_t* matrix, int rows, int cols) {
    for (int i = 0; i < rows / 2; i++) {
        for (int j = 0; j < cols; j++) {
            elem_t temp = matrix[i * cols + j];
            matrix[i * cols + j] = matrix[(rows - 1 - i) * cols + j];
            matrix[(rows - 1 - i) * cols + j] = temp;
        }
    }
}
// Test matrices
static elem_t input_matrix[DIM * DIM] __attribute__((aligned(64)));
static elem_t transposed_matrix[DIM * DIM] __attribute__((aligned(64)));
static elem_t weight_matrix[DIM * DIM] __attribute__((aligned(64)));
static result_t output_matrix[DIM * DIM] __attribute__((aligned(64)));
static result_t expected_output_matrix[DIM * DIM] __attribute__((aligned(64)));
#define BANK 512
#define OP1_ADDR 0
#define OP2_ADDR (BANK + DIM)
#define WR_ADDR (DIM + 2 * BANK)

int main() {
#ifdef MULTICORE 
    multicore(MULTICORE);  // Only allow specified hart to continue
#endif

    // Initialize weight matrix
    init_matrix(weight_matrix, DIM, DIM, 42);
    init_matrix(input_matrix, DIM, DIM, 51);

    // Clear output matrix
    memset(output_matrix, 0, sizeof(output_matrix));
    cpu_matmul(input_matrix, weight_matrix, expected_output_matrix, DIM, DIM, DIM);
    //print_matrix("Input", input_matrix, DIM, DIM);
    
    // Move input to scratchpad
    bb_mvin((uintptr_t)output_matrix, WR_ADDR, DIM << 2);
    bb_mvin((uintptr_t)weight_matrix, OP1_ADDR, DIM );
    bb_mvin((uintptr_t)input_matrix, OP2_ADDR, DIM );
    bb_fence();
    bb_bbfp_mul(OP1_ADDR, OP2_ADDR, WR_ADDR, DIM);
    bb_fence();
    // Move back from scratchpad to output
    bb_mvout((uintptr_t)output_matrix, WR_ADDR, DIM << 2);
    bb_fence();
    if(compare_u32_matrices(output_matrix, expected_output_matrix, DIM, DIM)) {
        printf("Test passed!\n");
    } else {
        printf("Test failed!\n");
    }
    // print_matrix("Output", output_matrix, DIM, DIM);

#ifdef MULTICORE 
    exit(0);
#endif
}
