import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {

 /*
 * [3:39 AM] Revanth Siva
Determine if a 9 x 9 Sudoku board is valid. Only the filled cells need to be validated according to the following rules:
Each row must contain the digits 1-9 without repetition.
Each column must contain the digits 1-9 without repetition.
Each of the nine 3 x 3 sub-boxes of the grid must contain the digits 1-9 without repetition.
Note:
A Sudoku board (partially filled) could be valid but is not necessarily solvable.
Only the filled cells need to be validated according to the mentioned rules.

Example 1:
Input: board =
[["5","3",".",".","7",".",".",".","."]
,["6",".",".","1","9","5",".",".","."]
,[".","9","8",".",".",".",".","6","."]
,["8",".",".",".","6",".",".",".","3"]
,["4",".",".","8",".","3",".",".","1"]
,["7",".",".",".","2",".",".",".","6"]
,[".","6",".",".",".",".","2","8","."]
,[".",".",".","4","1","9",".",".","5"]
,[".",".",".",".","8",".",".","7","9"]]
Output: true
Example 2:
Input: board =
[["8","3",".",".","7",".",".",".","."]
,["6",".",".","1","9","5",".",".","."]
,[".","9","8",".",".",".",".","6","."]
,["8",".",".",".","6",".",".",".","3"]
,["4",".",".","8",".","3",".",".","1"]
,["7",".",".",".","2",".",".",".","6"]
,[".","6",".",".",".",".","2","8","."]
,[".",".",".","4","1","9",".",".","5"]
,[".",".",".",".","8",".",".","7","9"]]
Output: false
Explanation: Same as Example 1, except with the 5 in the top left corner being modified to 8. Since there are two 8's in the top left 3x3 sub-box, it is invalid.

Constraints:
board.length == 9
board[i].length == 9
board[i][j] is a digit 1-9 or '.'.
*
* ["5","3","]
* ["5","3","]
* ["5","3","]
*
*
*  0
*  2
*  5
*  8
*
*
*
*
*
* */



    public static void main(String[] args) {
        System.out.println("Hello world!");
    }


    public boolean validSudoko(int[][] board){
        List<Set<Integer>> rows = new ArrayList<>();
        List<Set<Integer>> cols = new ArrayList<>();
        List<Set<Integer>> logicalMatrix = new ArrayList<>();

        for(int i = 0; i < 9;i++){
            for(int j =0; j <9; j++) {
                int curNumber = board[i][j];
                Set<Integer> row=rows.get(i);
                Set<Integer> col = cols.get(j);
                int n = calLogicalMatrix(i, j);
                Set<Integer> vMatrix = logicalMatrix.get(n);
                if(row.contains(curNumber) && col.contains(curNumber)
                && vMatrix.contains(curNumber)){
                    return false;
                }
                else {
                    row.add(curNumber);
                    col.add(curNumber);
                    vMatrix.add(curNumber);
                }
            }
        }
        return true;
    }
/*
* [["8","3",".",".","7",".",".",".","."]
,["6",".",".","1","9","5",".",".","."]
,[".","9","8",".",".",".",".","6","."]
,["8",".",".",".","6",".",".",".","3"]
,["4",".",".","8",".","3",".",".","1"]
,["7",".",".",".","2",".",".",".","6"]
,[".","6",".",".",".",".","2","8","."]
,[".",".",".","4","1","9",".",".","5"]
,[".",".",".",".","8",".",".","7","9"]]

*
* */
    public int calLogicalMatrix(int i, int j){
        int vNumber=0;
        int starti =0;
        int startJ =0;
        for(int k = 0; k < 3; k++){
            if(i >=0 && i <3  && j >=0 && j < 3){
                return vNumber;
            }
            if(i >=3 && i <6  && j >=3 && j < 6){
                return ++vNumber;
            }
            if(i >=6 && i <9  && j >=6 && j < 9){
                return ++vNumber;
            }
        }
        return vNumber;
    }
}