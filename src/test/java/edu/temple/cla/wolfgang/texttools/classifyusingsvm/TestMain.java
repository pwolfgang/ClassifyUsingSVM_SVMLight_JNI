/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.temple.cla.wolfgang.texttools.classifyusingsvm;

import org.junit.Test;

/**
 *
 * @author Paul
 */
public class TestMain {
    
    @Test
    public void testMain() {
        Main.main(new String[] {"--datasource", "..\\..\\policydbproject\\PAPolicy_Copy.txt",
                "--table_name", "(select * from Bills_Data_2015_16) as Bills",
                "--id_column", "ID",
                "--text_column", "Abstract",
                "--code_column", "Code",
                "--output_code_col", "SVMMinor",
                "--model", "..\\..\\policydbproject\\TestSVM\\SVM_CodeModel_9"});
    }
    
}
