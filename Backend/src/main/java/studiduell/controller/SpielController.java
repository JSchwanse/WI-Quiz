package studiduell.controller;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import studiduell.constants.entity.SpielstatusEntityEnum;
import studiduell.constants.entity.SpieltypEntityEnum;
import studiduell.json.model.RoundResultPOJO;
import studiduell.misc.QuestionsSorter;
import studiduell.model.AntwortEntity;
import studiduell.model.FrageEntity;
import studiduell.model.KategorieEntity;
import studiduell.model.KategorienfilterEntity;
import studiduell.model.RundeEntity;
import studiduell.model.SpielEntity;
import studiduell.model.SpielstatusEntity;
import studiduell.model.SpieltypEntity;
import studiduell.model.UserEntity;
import studiduell.repository.AntwortRepository;
import studiduell.repository.FrageRepository;
import studiduell.repository.KategorieRepository;
import studiduell.repository.KategorienfilterRepository;
import studiduell.repository.RundeRepository;
import studiduell.repository.SpielRepository;
import studiduell.repository.UserRepository;
import studiduell.security.CurrentUsername;

@Controller
@Transactional(rollbackFor=RuntimeException.class)
@RequestMapping(value = "/game")
public class SpielController {
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SpielRepository spielRepository;
	@Autowired
	private RundeRepository rundeRepository;
	@Autowired
	private AntwortRepository antwortRepository;
	@Autowired
	private FrageRepository frageRepository;
	@Autowired
	private KategorieRepository kategorieRepository;
	@Autowired
	private KategorienfilterRepository kategorienfilterRepository;
	@Autowired
	private QuestionsSorter questionsSorter;
	
	private Random random = new Random();
	
	@Value("${game.calendar.userActivityTimeout.field}")
	private int calendarField;
	@Value("${game.calendar.userActivityTimeout.offset}")
	private int calendarOffset;
	@Value("${game.maxRounds}")
	private int maxRounds;
	
	@Value("${game.suggestedCategoriesCount}")
	private int suggestedCategoriesCount;
	@Value("${game.questionsPerRound}")
	private int questionsPerRound;
	
	@RequestMapping(method = RequestMethod.POST, value = "/create/with/{opponent}")
	public synchronized ResponseEntity<Void> create(@PathVariable("opponent") String opponent,
			@CurrentUsername String authUsername) {
		UserEntity userUserEntity = userRepository.findOne(authUsername);
		UserEntity opponentUserEntity = userRepository.findOne(opponent);
		
		if(opponentUserEntity != null) {
			// do I challenge myself?
			// does an active game with the opponent exist at the time?
			if(!userUserEntity.getBenutzername().equals(opponent)
					&& spielRepository.getWithUserAndOpponentInStatus(userUserEntity,
					opponentUserEntity,
					Arrays.asList(new SpielstatusEntity[] {SpielstatusEntityEnum.A.getEntity(), SpielstatusEntityEnum.P.getEntity()})) == 0) {
				// do we have at least three categories in common
				Set<KategorienfilterEntity> commonCategories = kategorienfilterRepository.commonCategories(userUserEntity, opponentUserEntity);
				if(commonCategories.size() >= suggestedCategoriesCount) {
					SpielEntity game = createGame(userUserEntity, opponentUserEntity,
							SpieltypEntityEnum.M.getEntity(), SpielstatusEntityEnum.P.getEntity());
					spielRepository.save(game);
					return new ResponseEntity<>(HttpStatus.CREATED);
				} else {
					return new ResponseEntity<>(HttpStatus.NOT_ACCEPTABLE);
				}
			} else {
				return new ResponseEntity<>(HttpStatus.CONFLICT);
			}
		} else {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}
	
	@RequestMapping(method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE,
			value = "/overview/{gameID}")
	public ResponseEntity<ObjectNode> gameOverview(@PathVariable("gameID") Integer gameID) {
		SpielEntity spielEntity = spielRepository.findOne(gameID);
		
		if(spielEntity != null) {
			List<RundeEntity> rounds = spielEntity.getRunden();
			
			ObjectNode json = JsonNodeFactory.instance.objectNode();
			
			// rounds - answers - questions
			ArrayNode roundsArray = JsonNodeFactory.instance.arrayNode();
			for(RundeEntity e : rounds) {
				roundsArray.addPOJO(e);
			}
			
			json.putPOJO("rounds", roundsArray);
			
			return new ResponseEntity<>(json, HttpStatus.OK);
		} else {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}
	
	@RequestMapping(method = RequestMethod.POST, consumes = MediaType.TEXT_PLAIN_VALUE,
			value = "/answerInvite/{gameID}")
	public ResponseEntity<Void> answerInvite(@PathVariable("gameID") Integer gameID, @RequestBody String flag,
			@CurrentUsername String authUsername) {
		boolean flagVal;
		
		if (flag.equalsIgnoreCase("true")) {
			flagVal = true;
		} else if (flag.equalsIgnoreCase("false")) {
			flagVal = false;
		} else {
			return new ResponseEntity<>(HttpStatus.NOT_ACCEPTABLE);
		}
		
		SpielEntity gameSpielEntity = spielRepository.findOne(gameID);
		if (gameSpielEntity != null) {
			// only accept invite if it is a pending game whose player 2 is this
			// user
			if (gameSpielEntity.getSpieler2().getBenutzername()
					.equals(authUsername)
					&& gameSpielEntity.getSpielstatusName().getName() == SpielstatusEntityEnum.P
							.getEntity().getName()) {

				if (flagVal) {
					// accept challenge
					gameSpielEntity.setSpielstatusName(SpielstatusEntityEnum.A
							.getEntity());

					// create rounds
					createRounds(gameSpielEntity);
				} else {
					// decline challenge
					gameSpielEntity.setSpielstatusName(SpielstatusEntityEnum.D
							.getEntity());
				}
				// set last activity to now
				gameSpielEntity.setLetzteAktivitaet(new Timestamp(System.currentTimeMillis()));
				
				spielRepository.save(gameSpielEntity);

				return new ResponseEntity<>(HttpStatus.OK);
			}
			return new ResponseEntity<>(HttpStatus.NOT_ACCEPTABLE);
		}

		return new ResponseEntity<>(HttpStatus.NOT_FOUND);
	}
	
	@RequestMapping(method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE,
			value = "/randomCategoriesFor/{gameID}")
	public ResponseEntity<ArrayNode> randomCategories(@PathVariable("gameID") Integer gameID,
			@CurrentUsername String authUsername) {
		SpielEntity gameSpielEntity = spielRepository.findOne(gameID);
		UserEntity userUserEntity = userRepository.findOne(authUsername);
		
		if(userUserEntity.equals(gameSpielEntity.getSpieler1()) || userUserEntity.equals(gameSpielEntity.getSpieler2())) {
			/*
			 * Ignore Kategorienfilter entities data. Every player forcibly selects every category provided.
			 */
			List<KategorieEntity> categories = kategorieRepository.findAll();
			categories = randomFromList(categories, suggestedCategoriesCount);
			
			if(categories.size() >= suggestedCategoriesCount) {
				// the certain amount of common categories exists
				
				ArrayNode json = JsonNodeFactory.instance.arrayNode();
				
				Iterator<KategorieEntity> categoryIterator = categories.iterator();
				while(categoryIterator.hasNext()) {
					// create an array entry for each selected category
					ObjectNode currEntryNode = JsonNodeFactory.instance.objectNode();
					KategorieEntity currCategory = categoryIterator.next();
					
					List<FrageEntity> questions = randomQuestionsByCategory(currCategory, questionsPerRound);
					ArrayNode questionsArrayNode = JsonNodeFactory.instance.arrayNode();
					for(Object question : questions.toArray()) {
						questionsArrayNode.addPOJO((FrageEntity) question);
					}
					
					currEntryNode.put("categoryName", currCategory.getName());
					currEntryNode.put("questions", questionsArrayNode);
					
					json.add(currEntryNode);
				}
				
				return new ResponseEntity<ArrayNode>(json, HttpStatus.OK);
			} else {
				// less than a certain amount of common categories
				return new ResponseEntity<>(HttpStatus.GONE);
			}
		} else {
			// any user requested questions for a game he does not play in
			return new ResponseEntity<>(HttpStatus.FORBIDDEN);
		}
	}
	
	@RequestMapping(method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE,
			value = "/continueRound/{gameID}")
	public ResponseEntity<ObjectNode> continueRound(@PathVariable("gameID") Integer gameID) {
		SpielEntity gameSpielEntity = spielRepository.findOne(gameID);
		
		if(gameSpielEntity != null) {
			RundeEntity roundRundeEntity = rundeRepository.findBySpielAndRundenNr(gameSpielEntity, gameSpielEntity.getAktuelleRunde());
			List<AntwortEntity> answers = roundRundeEntity.getAnswers();
			if(!answers.isEmpty()) {
				// build server answer
				ObjectNode json = JsonNodeFactory.instance.objectNode();
				ArrayNode questionsNode = JsonNodeFactory.instance.arrayNode();
				ArrayNode answersNode = JsonNodeFactory.instance.arrayNode();
				
				for(AntwortEntity ans : answers) {
					questionsNode.addPOJO(ans.getFrage());
					answersNode.addPOJO(ans);
				}
				
				json.put("questions", questionsNode);
				json.put("answers", answersNode);
				
				return new ResponseEntity<ObjectNode>(json, HttpStatus.OK);
			} else {
				return new ResponseEntity<>(HttpStatus.NOT_FOUND);
			}
		} else {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}
	
	@RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE,
			value = "/submitRoundResult/{gameID}")
	public ResponseEntity<Void> submitRoundResult(@PathVariable("gameID") Integer gameID,
			@RequestBody RoundResultPOJO[] roundResult, @CurrentUsername String authUsername) {
		UserEntity userUserEntity = userRepository.findOne(authUsername);
		SpielEntity gameSpielEntity = spielRepository.findOne(gameID);
		
		if(gameSpielEntity != null) {
			String tmpSpieler1Name = gameSpielEntity.getSpieler1().getBenutzername();
			String tmpSpieler2Name = gameSpielEntity.getSpieler2().getBenutzername();
			
			// does player participate that game? Is it his turn, meaning he is allowed to submit round results
			if((tmpSpieler1Name.equals(authUsername) || tmpSpieler2Name.equals(authUsername))
					&& gameSpielEntity.getWartenAuf().getBenutzername().equals(authUsername)) {
				UserEntity opponentUserEntity = userRepository.findOne(
						authUsername.equals(tmpSpieler1Name) ? tmpSpieler2Name : tmpSpieler1Name);
				// did the player send exactly that amount of questions the server expects
				if(roundResult.length == questionsPerRound) {
					Boolean roundStarter = null;
					for(RoundResultPOJO currResult : roundResult) {
						RundeEntity roundRundeEntity = rundeRepository.findBySpielAndRundenNr(gameSpielEntity, currResult.getRunde());
						FrageEntity questionFrageEntity = frageRepository.findOne(currResult.getFragenID());
						// does the submitted question id number really denote a valid question?
						if(questionFrageEntity != null) {
							// round starter if no answers for this current round exist
							roundStarter = roundRundeEntity.getAnswers().isEmpty();
							
							AntwortEntity answerAntwortEntity = new AntwortEntity();
							answerAntwortEntity.setFrage(questionFrageEntity);
							answerAntwortEntity.setRundenID(roundRundeEntity);
							answerAntwortEntity.setBenutzer(userUserEntity);
							answerAntwortEntity.setAntwortmoeglichkeit1Check(currResult.isAntwortmoeglichkeit1Check());
							answerAntwortEntity.setAntwortmoeglichkeit2Check(currResult.isAntwortmoeglichkeit2Check());
							answerAntwortEntity.setAntwortmoeglichkeit3Check(currResult.isAntwortmoeglichkeit3Check());
							answerAntwortEntity.setAntwortmoeglichkeit4Check(currResult.isAntwortmoeglichkeit4Check());
							answerAntwortEntity.setFlagFrageAngezeigt(true);
							answerAntwortEntity.setErgebnisCheck(currResult.isErgebnisCheck());
							
							antwortRepository.save(answerAntwortEntity);
							
							
						} else {
							return new ResponseEntity<Void>(HttpStatus.NOT_ACCEPTABLE);
						}
					}
					
					if(roundStarter != null) {
						if(roundStarter) {
							// update wartenAuf
							gameSpielEntity.setWartenAuf(opponentUserEntity);
						} else {
							if(gameSpielEntity.getAktuelleRunde() != maxRounds) {
								// increment round, as the current is finished
								gameSpielEntity.setAktuelleRunde(gameSpielEntity.getAktuelleRunde() + 1);
							} else {
								// finished last round, game over
								gameSpielEntity.setWartenAuf(null);
								gameSpielEntity.setSpielstatusName(SpielstatusEntityEnum.C.getEntity());
							}
						}
					}
					
					spielRepository.save(gameSpielEntity);
					return new ResponseEntity<Void>(HttpStatus.OK);
				} else {
					return new ResponseEntity<Void>(HttpStatus.EXPECTATION_FAILED);
				}
			} else {
				return new ResponseEntity<Void>(HttpStatus.FORBIDDEN);
			}
		} else {
			return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
		}
	}
	
	@RequestMapping(method = RequestMethod.POST, value = "/abandon/{gameID}")
	public ResponseEntity<Void> abandon(@PathVariable("gameID") Integer gameID,
			@CurrentUsername String authUsername) {
		UserEntity userUserEntity = userRepository.findOne(authUsername);
		SpielEntity gameSpielEntity = spielRepository.findOne(gameID);
		
		if(gameSpielEntity != null) {
			SpielstatusEntity gameState = gameSpielEntity.getSpielstatusName();
			if(SpielstatusEntityEnum.A.getEntity().equals(gameState)
					|| SpielstatusEntityEnum.P.getEntity().equals(gameState)) {
				
				gameSpielEntity.setSpielstatusName(SpielstatusEntityEnum.Q.getEntity());
				// the player that has abandoned the game, is now written in "wartenAuf"
				gameSpielEntity.setWartenAuf(userUserEntity);
				spielRepository.save(gameSpielEntity);
				
				
				return new ResponseEntity<Void>(HttpStatus.OK); 
			} else {
				// only active and pending games can be abandoned
				return new ResponseEntity<Void>(HttpStatus.NOT_ACCEPTABLE);
			}
		} else {
			// no such game found
			return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
		}
	}
	
	private SpielEntity createGame(UserEntity user, UserEntity opponent, SpieltypEntity type, SpielstatusEntity status) {
		SpielEntity game = new SpielEntity();
		game.setSpieltypName(type);
		game.setSpieler1(user);
		game.setSpieler2(opponent);
		game.setWartenAuf(opponent);
		game.setAktuelleRunde(1);
		game.setSpielstatusName(status);
		game.setLetzteAktivitaet(new Timestamp(System.currentTimeMillis()));
		
		return game;
	}
	
	private void createRounds(SpielEntity spielEntity) {
		for(int i = 1; i <= maxRounds; i++) {
			RundeEntity round = new RundeEntity(spielEntity, i);
			rundeRepository.save(round);
		}
	}
	
	private List<FrageEntity> randomQuestionsByCategory(KategorieEntity category, int questionCount) {
		List<FrageEntity> questions = frageRepository.findByKategorieNameAndFlagFrageValidiertIsTrue(category);
		Set<Integer> indices = new HashSet<>(questionCount);
		List<FrageEntity> selectedQuestions = new ArrayList<>(questionCount);
			
		// pick random indices that denote questions
		do {
			indices.add(random.nextInt(questions.size()));
		} while(indices.size() != questionCount);
		
		Integer[] tmpIndicesArray = indices.toArray(new Integer[questionCount]);
		// pick the questions that are identified by the determined indices
		for(int i = 0; i < tmpIndicesArray.length; i++) {
			selectedQuestions.add(questions.get(tmpIndicesArray[i]));
		}
		
		// sort by fragenID
		Collections.sort(selectedQuestions, questionsSorter);
		
		return selectedQuestions;
	}
	
	/**
	 * Returns a random intersection of the list.
	 * 
	 * @param allCats the elements' source
	 * @param elementsToKeep the amount of new elements
	 * @return a list with <code>elementsToKeep</code> elements, randomly selected
	 * from <code>allCats</code>
	 */
	private List<KategorieEntity> randomFromList(List<KategorieEntity> allCats, int elementsToKeep) {
		Set<KategorieEntity> newSet = new HashSet<>(elementsToKeep);
		Random random = new Random();
		
		while(newSet.size() < elementsToKeep) {
			KategorieEntity category = allCats.get(random.nextInt(allCats.size()));
			newSet.add(category);
		}
		
		return new ArrayList<>(newSet);
	}
}