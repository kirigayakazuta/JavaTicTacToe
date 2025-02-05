package com.example.TicTacToe.domain.service;

import com.example.TicTacToe.datasource.repository.GameRepository;
import com.example.TicTacToe.domain.exceptions.InvalidMoveException;
import com.example.TicTacToe.domain.exceptions.ResourceNotFoundException;
import com.example.TicTacToe.domain.model.Game;
import com.example.TicTacToe.web.dto.GameRequest;
import com.example.TicTacToe.web.dto.GameState;
import com.example.TicTacToe.web.dto.NewGameParam;
import com.example.TicTacToe.domain.exceptions.GameRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.example.TicTacToe.datasource.mapper.DataSourceDomainMapper.dsToDomainGame;
import static com.example.TicTacToe.datasource.mapper.DataSourceDomainMapper.toDsGame;
import static com.example.TicTacToe.web.dto.GameState.*;
import static com.example.TicTacToe.domain.service.GameLogic.*;

@Service
public class GameService {
    public static int SIZE = 3;
    public static final int X = 1;
    public static final int O = 2;

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    private GameState updateGameStatus(Game game) {
        int[][] field = game.getField();
        if (isWin(field, X)) return FIRST_PLAYER_WON;
        if (isWin(field, O)) return SECOND_PLAYER_WON;
        if (isDraw(field)) return DRAW;
        if (game.getGameState().equals(FIRST_PLAYER_TURN)) return SECOND_PLAYER_TURN;
        if (game.getGameState().equals(SECOND_PLAYER_TURN)) return FIRST_PLAYER_TURN;
        return game.getGameState();
    }

    public Game postMove(GameRequest gameRequest, UUID gameId, UUID userId) {
        Game game = getGameById(gameId);
        GameState gameState = game.getGameState();


        if (gameState == WAITING_FOR_PLAYERS) {
            throw new GameRequestException("We are waiting for players");
        }
        if (!game.isPlayWithAi() && ((gameState == FIRST_PLAYER_TURN && game.getSecondPlayer().equals(userId)) ||
                (game.getGameState() == SECOND_PLAYER_TURN && game.getFirstPlayer().equals(userId)))) {
            throw new GameRequestException("It's not your move now");
        }
        if (gameState == FIRST_PLAYER_WON || gameState == SECOND_PLAYER_WON || gameState == DRAW) {
            throw new GameRequestException("Game is over");
        }

        int icon;
        if (game.isPlayWithAi()) {
            if (game.getFirstPlayer() == null) {
                icon = O;
            } else {
                icon = X;
            }
        } else {
            if (game.getFirstPlayer().equals(userId)) {
                icon = X;
            } else {
                icon = O;
            }
        }

        int row = gameRequest.getRow();
        int col = gameRequest.getCol();
        if (row >= SIZE || col >= SIZE || row < 0 || col < 0) {
            throw new InvalidMoveException("Invalid row or column");
        }

        int[][] field = game.getField();
        if (field[row][col] != 0) {
            throw new InvalidMoveException(String.format("Cell (%d, %d) is occupied", row, col));
        }

        field[row][col] = icon;
        game.setGameState(updateGameStatus(game));

        if (game.isPlayWithAi()) {
            game = aiDoMove(game);
        }
        saveGame(game);
        return game;
    }

    public Game aiDoMove(Game game) {
        int player, computer;
        if (game.getFirstPlayer() == null) {
            player = O;
            computer = X;
        } else {
            player = X;
            computer = O;
        }
        int[] bestMove = findBestMove(game.getField(), player, computer);
        game.getField()[bestMove[0]][bestMove[1]] = computer;
        game.setGameState(updateGameStatus(game));
        return game;
    }

    public Game getGameById(UUID id) {
        return dsToDomainGame(gameRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Game with id " + id + " not found")));
    }

    public void saveGame(Game domainGame) {
        gameRepository.save(toDsGame(domainGame));
    }


    public List<UUID> getFreeGames() {
        Pageable pageable = PageRequest.of(0, 10);
        return gameRepository.findFreeGames(pageable);
    }

    public Game getNewGame(NewGameParam param, UUID userId) {
        int icon = param.getIcon();
        boolean ai = param.isAi();
        Game game = new Game();
        game.setId(UUID.randomUUID());
        game.setField(new int[SIZE][SIZE]);

        if (!(icon == X || icon == O)) {
            throw new IllegalArgumentException("Invalid icon");
        }
        if (icon == X) {
            game.setFirstPlayer(userId);
        } else {
            game.setSecondPlayer(userId);
        }

        game.setPlayWithAi(ai);

        if (ai) {
            game.setGameState(FIRST_PLAYER_TURN);
            if (icon == O) {
                game = aiDoMove(game);
            }
        } else {
            game.setGameState(WAITING_FOR_PLAYERS);
        }
        saveGame(game);
        return game;
    }

    public void joinGame(UUID gameId, UUID userId) {
        Game game = getGameById(gameId);
        if (!game.getGameState().equals(WAITING_FOR_PLAYERS)) {
            throw new GameRequestException("This game is busy");
        }
        if (game.getFirstPlayer() == null) {
            game.setFirstPlayer(userId);
        }
        if (game.getSecondPlayer() == null) {
            game.setSecondPlayer(userId);
        }
        if (game.getFirstPlayer().equals(userId) && game.getSecondPlayer().equals(userId)) {
            throw new GameRequestException("You can't play with yourself");
        }
        game.setGameState(FIRST_PLAYER_TURN);
        saveGame(game);
    }

    public Game getGame(UUID gameId, UUID userId) {
        Game game = getGameById(gameId);
        if (!(userId.equals(game.getFirstPlayer()) || userId.equals(game.getSecondPlayer()))) {
            throw new GameRequestException("You do not have access to this game");
        }
        return game;
    }
}
