package org.broadinstitute.hellbender.tools.walkers.varianteval;

import htsjdk.samtools.metrics.MetricsFile;
import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.hellbender.metrics.MetricsUtils;
import org.broadinstitute.hellbender.metrics.analysis.AlleleFrequencyQCMetric;
import org.broadinstitute.hellbender.tools.walkers.varianteval.stratifications.AlleleFrequency.StratifyingScale;
import org.broadinstitute.hellbender.utils.R.RScriptExecutor;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.report.GATKReport;
import org.broadinstitute.hellbender.utils.report.GATKReportTable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *  This tool uses Variant Eval to bin variants in Thousand Genomes to compare average variant allele frequencies
 *  between a new VCF and expected allele frequencies from Thousand Genomes. This was designed with arrays in mind,
 *  as a way to discover potential bugs in our pipeline. It uses the results from the variant eval to generate a
 *  simplified metric that returns a modified chi squared statistic (sum of the squared difference between the two allele
 *  frequencies in each bin, allowing a constant variance) as well as its associated p-value. The original variant eval
 *  results can be returned by giving the debug variable a filename
 * **/
public class AlleleFrequencyQC extends VariantEval {

    @Argument(shortName = "pvalue-threshold",
            doc = "Threshold to cut off the pvalue.")
    protected Double threshold = 0.05;

    @Argument(shortName = "allowed-variance",
            doc = "Variance allowed in calculating the modified chi squared statistic.")
    protected Double allowedVariance = 0.01;

    @Argument(shortName = "debug-file",
            doc = "File to save the results from variant eval for debugging", optional = true)
    protected File debugFile;

    private String R_SCRIPT = "plotAlleleFrequencyQC.R";
    protected File metricOutput;
    protected String sample;

    @Override
    public void onTraversalStart() {
        NO_STANDARD_MODULES = true;
        MODULES_TO_USE = Collections.singletonList("PVariantEvaluator");
        keepSitesWithAC0 = true;
        NO_STANDARD_STRATIFICATIONS = true;
        STRATIFICATIONS_TO_USE = Arrays.asList("AlleleFrequency", "Filter");
        AFScale = StratifyingScale.LOGARITHMIC;
        useCompAFStratifier = true;
        metricOutput = outFile; // output file for summary metrics

        // have to set the output file for variant eval; if not given a debug file to return the variant eval results
        // from, this will just be a temporary file that wil lbe deleted after the tool runs
        try {
            outFile = debugFile == null ? File.createTempFile("variant_eval" ,".txt") : debugFile;
        } catch (IOException e) {
            throw new RuntimeException("Trouble creating temporary variant eval file", e);
        }
        if (debugFile == null) {
            outFile.deleteOnExit();
        }
        sample = getHeaderForVariants().getOtherHeaderLine("sampleAlias").getValue();
        super.onTraversalStart();
    }

    @Override
    public Object  onTraversalSuccess() {

        super.onTraversalSuccess();

        GATKReportTable table= new GATKReport(outFile).getTable(MODULES_TO_USE.get(0));
        List<String> columnNames = table.getColumnInfo().stream().map(c -> c.getColumnName()).collect(Collectors.toList());

        // this is a map of allele frequency bin : length 2 list of observed allele frequencies ( one for comp, one for eval )
        Map<Object, List<Object>> afMap = IntStream.range(0, table.getNumRows()).mapToObj(i -> table.getRow(i)).
                filter(r -> r[columnNames.indexOf("Filter")].equals("called")).
                collect(Collectors.groupingBy(r -> r[columnNames.indexOf("AlleleFrequency")],
                        Collectors.mapping(r -> r[columnNames.indexOf("avgVarAlleles")], Collectors.toList())));

        ChiSquaredDistribution dist = new ChiSquaredDistribution(afMap.size()-1);
        Double chiSqValue = calculateChiSquaredStatistic(afMap, allowedVariance);
        Double pVal = 1- dist.cumulativeProbability(chiSqValue);
        MetricsFile<AlleleFrequencyQCMetric, Integer> metricsFile = new MetricsFile<>();
        final AlleleFrequencyQCMetric metric = new AlleleFrequencyQCMetric();

        metric.SAMPLE = sample;
        metric.CHI_SQ_VALUE =  chiSqValue;
        metric.METRIC_TYPE = "Allele Frequency";
        metric.METRIC_VALUE = pVal;

        metricsFile.addMetric(metric);
        MetricsUtils.saveMetrics(metricsFile,   metricOutput.getAbsolutePath());

        // need the file returned from variant eval in order to run the plotting stuff
        final RScriptExecutor executer = new RScriptExecutor();
        executer.addScript(new Resource(R_SCRIPT, AlleleFrequencyQC.class));
        executer.addArgs(outFile.getAbsolutePath() , metricOutput.getAbsolutePath(), sample);
        executer.exec();

        if (pVal < threshold) {
            logger.error("Allele frequencies between your array VCF and the expected VCF do not match with a significant pvalue of " + pVal);
        }
        return null;
    }

    // This creates a modified chi squared statistic that allows for a constant variance (1%) rather than scaling
    // with the expected count values
    private Double calculateChiSquaredStatistic(Map<Object, List<Object>> afMap, Double variance) {
        return afMap.values().stream().
                mapToDouble(afs -> afs.size() >= 2 ?
                        Math.pow((Double)afs.toArray()[0] -  (Double)afs.toArray()[1], 2.) : 0).
                sum()/Math.pow(variance, 2.);
    }

}
