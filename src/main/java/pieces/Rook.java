package pieces;

import board.Board;
import board.Location;
import game.Color;
import game.Move;

/**
 * Rook class that represents the rook piece.
 *
 * @author knorms
 *
 */
public class Rook extends Piece {
	
	public Rook(Color color) {
		super(color);
	}
	
	@Override
	public boolean move(Move move, Board board) {
		Location start = move.getStart();
		Location end = move.getEnd();
		
		if(start.getRow() != end.getRow() && start.getCol() != end.getCol()) {
			return false;
		}
		if(isSame(start, end)) {
			return false;
		}
		int colDir = (int) Math.signum(end.getCol() - start.getCol());
		int rowDir = (int) Math.signum(end.getRow() - start.getRow());
		if(rowDir != 0 && colDir != 0) {
			return false;
		}
		return checkInLine(start, end, board, rowDir, colDir);
	}
}
