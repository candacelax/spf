/*******************************************************************************
 * UW SPF - The University of Washington Semantic Parsing Framework
 * <p>
 * Copyright (C) 2013 Yoav Artzi
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 ******************************************************************************/
package edu.uw.cs.lil.tiny.mr.lambda;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.AndSimplifier;
import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.ArrayIndexAccessSimplifier;
import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.IPredicateSimplifier;
import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.IncSimplifier;
import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.NotSimplifier;
import edu.uw.cs.lil.tiny.mr.lambda.primitivetypes.OrSimplifier;
import edu.uw.cs.lil.tiny.mr.language.type.ArrayType;
import edu.uw.cs.lil.tiny.mr.language.type.ComplexType;
import edu.uw.cs.lil.tiny.mr.language.type.Type;
import edu.uw.cs.lil.tiny.mr.language.type.TypeRepository;
import edu.uw.cs.lil.tiny.utils.LispReader;
import edu.uw.cs.utils.collections.ListUtils;
import edu.uw.cs.utils.composites.Pair;
import edu.uw.cs.utils.log.ILogger;
import edu.uw.cs.utils.log.LoggerFactory;

/**
 * Logical expression meaning representation services.
 * 
 * @author Yoav Artzi
 */
public class LogicLanguageServices {
	public static final ILogger									LOG									= LoggerFactory
																											.create(LogicLanguageServices.class);
	
	private static final String									ARRAY_INDEX_ACCESS_PREDICATE_NAME	= "i";
	
	private static final String									ARRAY_SUB_PREDICATE_NAME			= "sub";
	private static LogicLanguageServices						INSTANCE							= null;
	
	/**
	 * Generic simplifier for index access predicates for any type of array.
	 */
	private final IPredicateSimplifier							arrayIndexPredicateSimplifier		= ArrayIndexAccessSimplifier.INSTANCE;
	
	/**
	 * Logical constant that might be removed from the logical form during
	 * simplification without changing its meaning. Several dynamic constants
	 * are not covered by this set, see
	 * {@link #isCollpasibleConstant(LogicalConstant)}.
	 */
	private final Set<LogicalConstant>							collapsibleConstants				= new HashSet<LogicalConstant>();
	private final LogicalConstant								conjunctionPredicate;
	private final LogicalConstant								disjunctionPredicate;
	private final LogicalConstant								falseConstant;
	
	private final LogicalConstant								indexIncreasePredicate;
	
	private final LogicalConstant								negationPredicate;
	
	/**
	 * If the system supports numeral types, this should be set to the base
	 * type.
	 */
	private final Type											numeralType;
	
	private final Ontology										ontology;
	
	private final Map<LogicalConstant, IPredicateSimplifier>	simplifiers							= new ConcurrentHashMap<LogicalConstant, IPredicateSimplifier>();
	
	private final LogicalConstant								trueConstant;
	
	/**
	 * A special comparator for types that allows comparing types for various
	 * cases, such as comparing the type of an argument to the signature type.
	 */
	private final ITypeComparator								typeComparator;
	
	private final TypeRepository								typeRepository;
	
	private LogicLanguageServices(TypeRepository typeRepository,
			String numeralTypeName, ITypeComparator typeComparator,
			Ontology ontology, LogicalConstant conjunctionPredicate,
			LogicalConstant disjunctionPredicate,
			LogicalConstant negationPredicate,
			LogicalConstant indexIncreasePredicate,
			LogicalConstant trueConstant, LogicalConstant falseConstant) {
		this.typeRepository = typeRepository;
		this.ontology = ontology;
		this.numeralType = numeralTypeName == null ? null : typeRepository
				.getType(numeralTypeName);
		this.typeComparator = typeComparator;
		
		// Basic predicates
		this.conjunctionPredicate = conjunctionPredicate;
		this.disjunctionPredicate = disjunctionPredicate;
		this.negationPredicate = negationPredicate;
		this.indexIncreasePredicate = indexIncreasePredicate;
		
		// Predicate specific simplifiers
		this.setSimplifier(conjunctionPredicate, AndSimplifier.INSTANCE, true);
		this.setSimplifier(disjunctionPredicate, OrSimplifier.INSTANCE, true);
		this.setSimplifier(negationPredicate, NotSimplifier.INSTANCE, true);
		this.setSimplifier(indexIncreasePredicate, IncSimplifier.INSTANCE,
				false);
		
		// Special constants
		this.trueConstant = trueConstant;
		this.falseConstant = falseConstant;
		this.collapsibleConstants.add(trueConstant);
		this.collapsibleConstants.add(falseConstant);
	}
	
	/**
	 * Compute the return type and implied signature type of the literal.
	 * 
	 * @return (return type, implied signature)
	 */
	public static Pair<Type, List<Type>> computeLiteralTyping(
			ComplexType predicateType, List<Type> argTypes) {
		return Literal.computeLiteralTyping(predicateType, argTypes,
				INSTANCE.typeComparator, INSTANCE.typeRepository);
	}
	
	/**
	 * Convenience method for {@link #computeLiteralTyping(ComplexType, List)}.
	 */
	public static Pair<Type, List<Type>> computeLiteralTypingFromArgs(
			ComplexType predicateType, List<LogicalExpression> args) {
		return Literal.computeLiteralTyping(predicateType, ListUtils.map(args,
				new ListUtils.Mapper<LogicalExpression, Type>() {
					
					@Override
					public Type process(LogicalExpression obj) {
						return obj.getType();
					}
				}), INSTANCE.typeComparator, INSTANCE.typeRepository);
	}
	
	public static LogicalConstant getConjunctionPredicate() {
		return INSTANCE.conjunctionPredicate;
	}
	
	public static LogicalConstant getDisjunctionPredicate() {
		return INSTANCE.disjunctionPredicate;
	}
	
	public static LogicalConstant getFalse() {
		return INSTANCE.falseConstant;
	}
	
	public static LogicalConstant getIndexIncreasePredicate() {
		return INSTANCE.indexIncreasePredicate;
	}
	
	static public LogicalConstant getIndexPredicateForArray(ArrayType arrayType) {
		final ComplexType predicateType = INSTANCE.typeRepository
				.getIndexPredicateTypeForArray(arrayType);
		final String name = LogicalConstant.makeName(
				ARRAY_INDEX_ACCESS_PREDICATE_NAME, predicateType);
		if (INSTANCE.ontology != null && INSTANCE.ontology.contains(name)) {
			return INSTANCE.ontology.get(name);
		} else {
			return LogicalConstant.createDynamic(name, predicateType);
		}
	}
	
	public static LogicalConstant getNegationPredicate() {
		return INSTANCE.negationPredicate;
	}
	
	public static Ontology getOntology() {
		return INSTANCE == null ? null : INSTANCE.ontology;
	}
	
	static public IPredicateSimplifier getSimplifier(LogicalExpression pred) {
		// First check if the given type is a index predicate, if so return the
		// generic index predicate simplifier.
		if (isArrayIndexPredicate(pred)) {
			// If this is a index function for an array, return the
			// generic
			// simplifier for index functions
			return INSTANCE.arrayIndexPredicateSimplifier;
		}
		
		return INSTANCE.simplifiers.get(pred);
	}
	
	static public LogicalConstant getSubPredicateForArray(ArrayType arrayType) {
		final ComplexType predicateType = INSTANCE.typeRepository
				.getSubPredicateTypeForArray(arrayType);
		final String name = LogicalConstant.makeName(ARRAY_SUB_PREDICATE_NAME,
				predicateType);
		if (INSTANCE.ontology != null && INSTANCE.ontology.contains(name)) {
			return INSTANCE.ontology.get(name);
		} else {
			return LogicalConstant.createDynamic(name, predicateType);
		}
	}
	
	public static LogicalConstant getTrue() {
		return INSTANCE.trueConstant;
	}
	
	public static ITypeComparator getTypeComparator() {
		return INSTANCE.typeComparator;
	}
	
	public static TypeRepository getTypeRepository() {
		return INSTANCE.typeRepository;
	}
	
	/**
	 * @param constant
	 *            Assumes the constant is of type 'ind'
	 * @return
	 */
	static public int indexConstantToInt(LogicalConstant constant) {
		if (constant.getType() == LogicLanguageServices.getTypeRepository()
				.getIndexType()) {
			return Integer.valueOf(constant.getBaseName());
		} else {
			throw new LogicalExpressionRuntimeException(
					"Constant must be of index type: " + constant);
		}
	}
	
	public static LogicLanguageServices instance() {
		return INSTANCE;
	}
	
	static public LogicalConstant intToIndexConstant(int i) {
		final String name = i + Term.TYPE_SEPARATOR
				+ INSTANCE.typeRepository.getIndexType().getName();
		if (INSTANCE.ontology != null && INSTANCE.ontology.contains(name)) {
			return INSTANCE.ontology.get(name);
		} else {
			return LogicalConstant.createDynamic(name,
					INSTANCE.typeRepository.getIndexType());
		}
	}
	
	static public LogicalConstant intToLogicalExpression(int num) {
		final String name = String.valueOf(num) + ":"
				+ INSTANCE.numeralType.getName();
		if (INSTANCE.ontology != null && INSTANCE.ontology.contains(name)) {
			return INSTANCE.ontology.get(name);
		} else {
			return LogicalConstant.createDynamic(name, INSTANCE.numeralType);
		}
	}
	
	public static boolean isArrayIndexPredicate(LogicalExpression pred) {
		return pred instanceof LogicalConstant
				&& ((LogicalConstant) pred).getName()
						.startsWith(
								ARRAY_INDEX_ACCESS_PREDICATE_NAME
										+ Term.TYPE_SEPARATOR);
	}
	
	public static boolean isArraySubPredicate(LogicalExpression pred) {
		return pred instanceof LogicalConstant
				&& ((LogicalConstant) pred).getName().startsWith(
						ARRAY_SUB_PREDICATE_NAME + Term.TYPE_SEPARATOR);
	}
	
	/**
	 * Returns 'true' iff the constant may disappear form the logical form
	 * during simplification (without modifying the meaning of the logical
	 * form).
	 */
	public static boolean isCollpasibleConstant(LogicalExpression exp) {
		return INSTANCE.collapsibleConstants.contains(exp)
				|| isArraySubPredicate(exp);
	}
	
	/**
	 * Return 'true' iff the type is a predicate of 'and:t' or 'or:t'.
	 * 
	 * @param type
	 * @return
	 */
	static public boolean isCoordinationPredicate(LogicalExpression pred) {
		return pred.equals(INSTANCE.conjunctionPredicate)
				|| pred.equals(INSTANCE.disjunctionPredicate);
	}
	
	public static boolean isOntologyClosed() {
		return INSTANCE.ontology != null && INSTANCE.ontology.isClosed();
	}
	
	static public Integer logicalExpressionToInteger(LogicalExpression exp) {
		if (exp instanceof LogicalConstant
				&& exp.getType().isExtending(INSTANCE.numeralType)) {
			final LogicalConstant constant = (LogicalConstant) exp;
			try {
				return Integer.valueOf(constant.getBaseName());
			} catch (final NumberFormatException e) {
				// Ignore, just return null
			}
		}
		return null;
	}
	
	public static void setInstance(LogicLanguageServices logicLanguageServices) {
		INSTANCE = logicLanguageServices;
	}
	
	public void setSimplifier(LogicalConstant predicate,
			IPredicateSimplifier simplifier, boolean collapsable) {
		if (collapsable) {
			synchronized (collapsibleConstants) {
				collapsibleConstants.add(predicate);
			}
		}
		simplifiers.put(predicate, simplifier);
	}
	
	public static class Builder {
		
		private final List<File>		constantsFiles	= new LinkedList<File>();
		private String					numeralTypeName	= null;
		private boolean					ontologyClosed	= false;
		private final ITypeComparator	typeComparator;
		
		private final TypeRepository	typeRepository;
		
		/**
		 * @param typeRepository
		 *            Type repository to be used by the system.
		 */
		public Builder(TypeRepository typeRepository) {
			this(typeRepository, new StrictTypeComparator());
		}
		
		/**
		 * @param typeRepository
		 *            Type repository to be used by the system.
		 * @param typeComparator
		 *            Type comparator to be used to compare types through the
		 *            system. Setting this accordingly allows to ignore certain
		 *            distinctions between finer types.
		 */
		public Builder(TypeRepository typeRepository,
				ITypeComparator typeComparator) {
			this.typeRepository = typeRepository;
			this.typeComparator = typeComparator;
		}
		
		private static Set<LogicalConstant> readConstantsFromFile(File file,
				TypeRepository typeRepository) throws IOException {
			// First, strip the comments and prepare a clean LISP string to
			// parse
			final BufferedReader reader = new BufferedReader(new FileReader(
					file));
			final StringBuilder strippedFile = new StringBuilder();
			try {
				String line = null;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					line = line.split("\\s*//")[0];
					if (!line.equals("")) {
						strippedFile.append(line).append(" ");
					}
				}
			} finally {
				reader.close();
			}
			
			// Read the constants
			final Set<LogicalConstant> constants = new HashSet<LogicalConstant>();
			final LispReader lispReader = new LispReader(new StringReader(
					strippedFile.toString()));
			while (lispReader.hasNext()) {
				final LogicalConstant exp = LogicalConstant.doParse(
						lispReader.next(), typeRepository);
				constants.add(exp);
			}
			
			return constants;
		}
		
		private static Set<LogicalConstant> readConstantsFromFiles(
				List<File> files, TypeRepository typeRepository)
				throws IOException {
			final Set<LogicalConstant> constants = new HashSet<LogicalConstant>();
			for (final File file : files) {
				constants.addAll(readConstantsFromFile(file, typeRepository));
			}
			return constants;
		}
		
		/**
		 * Use an ontology. This allows to re-use {@link LogicalConstant}
		 * objects. If not ontology is defined, {@link LogicalConstant} objects
		 * are created just like any other objects.
		 */
		public Builder addConstantsToOntology(File constantsFile) {
			this.constantsFiles.add(constantsFile);
			return this;
		}
		
		/**
		 * Shortcut for {@link #addConstantsToOntology(File)}.
		 */
		public Builder addConstantsToOntology(
				@SuppressWarnings("hiding") List<File> constantsFiles) {
			this.constantsFiles.addAll(constantsFiles);
			return this;
		}
		
		public LogicLanguageServices build() throws IOException {
			// Basic predicates
			final LogicalConstant conjunctionPredicate = LogicalConstant.parse(
					"and:<t*,t>", typeRepository);
			final LogicalConstant disjunctionPredicate = LogicalConstant.parse(
					"or:<t*,t>", typeRepository);
			final LogicalConstant negationPredicate = LogicalConstant.parse(
					"not:<t,t>", typeRepository);
			final LogicalConstant indexIncreasePredicate = LogicalConstant
					.parse("inc:<" + typeRepository.getIndexType().getName()
							+ "," + typeRepository.getIndexType().getName()
							+ ">", typeRepository);
			
			// Special constants
			final LogicalConstant trueConstant = LogicalConstant.create(
					"true:t", typeRepository.getTruthValueType());
			final LogicalConstant falseConstant = LogicalConstant.create(
					"false:t", typeRepository.getTruthValueType());
			
			// Create the ontology if using one
			final Ontology ontology;
			if (constantsFiles.isEmpty()) {
				ontology = null;
				if (ontologyClosed) {
					throw new IllegalArgumentException(
							"Closed ontology requested, but no logical constants were provided.");
				}
			} else {
				final Set<LogicalConstant> constants = readConstantsFromFiles(
						constantsFiles, typeRepository);
				// Add all the above mentioned constants.
				constants.add(conjunctionPredicate);
				constants.add(disjunctionPredicate);
				constants.add(negationPredicate);
				constants.add(indexIncreasePredicate);
				constants.add(trueConstant);
				constants.add(falseConstant);
				ontology = new Ontology(constants, ontologyClosed);
			}
			
			return new LogicLanguageServices(typeRepository, numeralTypeName,
					typeComparator, ontology, conjunctionPredicate,
					disjunctionPredicate, negationPredicate,
					indexIncreasePredicate, trueConstant, falseConstant);
		}
		
		/**
		 * Create a closed ontology. {@link LogicalConstant} objects will be
		 * re-used and the set of available constants is closed once the LLS is
		 * built.
		 */
		public Builder closeOntology(boolean isClosed) {
			this.ontologyClosed = isClosed;
			return this;
		}
		
		/**
		 * Set the type used for numerical objects in the logical system. This
		 * type is used to convert such objects to numbers using
		 * {@link LogicLanguageServices#intToLogicalExpression(int)} and
		 * {@link LogicLanguageServices#logicalExpressionToInteger(LogicalExpression)}
		 * .
		 */
		public Builder setNumeralTypeName(String numeralTypeName) {
			this.numeralTypeName = numeralTypeName;
			return this;
		}
	}
	
}
