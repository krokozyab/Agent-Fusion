ALTER SESSION SET CURRENT_SCHEMA = "BIAPP_OBF6305";


SELECT e.mco_id, e.dk_org_cy_cnp, e.dk_nws_ct, e.dk_ced, e.obf_mmi_id, e.yr, e.exp_unt, e.own_id, e.expn_tp, e.obf_volte_id, e.t4t_id,
                               e.mco_grp_id, NVL(g.cpy_id, e.mco_id) cpy_id, (100 + e.pct) / 100 exp_pct
                          FROM t_trf_expn e
                     LEFT JOIN t_cpy_mco_sgrp g ON NVL(e.mco_grp_id, g.mco_grp_id) = g.mco_grp_id AND NVL(e.mco_id, g.cpy_id) = g.cpy_id;
                     
select * from t_bsc;
select * from t_wsc;
select * from t_scn_det;
select * from t_crrt_def;
select * from t_trf_expn;


declare
vDstTblNm          K_OBF_CMN.OraName;
lastMoWithAct      t_scn.dk_dat_mo_last_act%TYPE;
begin
    vDstTblNm:=k_obf_scn.getScnTblNm(588544);
    dbms_output.put_line(vDstTblNm);
    lastMoWithAct := k_obf_scn.getDkDatMoLastActInScn(588544);
    dbms_output.put_line(lastMoWithAct);
end;
/
--t_scn_det


SELECT t_scn_det tblNm, BSD tblCd
         FROM t_bsc
        WHERE scn_id = 588544;

/*SELECT *
      --INTO tblNm, tblCd
      FROM (
       SELECT K_OBF_CONST.TBL_NM_SCN_DET tblNm, K_OBF_CONST.TBL_CD_SCN_DET tblCd
         FROM t_bsc
        WHERE scn_id = 588544--scnId
        UNION ALL
       SELECT K_OBF_CONST.TBL_NM_WSC_DET, K_OBF_CONST.TBL_CD_WSC_DET
         FROM t_wsc
        WHERE scn_id = 588544;--scnId;
*/        
        
SELECT (cpy_id_mco mco_id, dk_org_cy_smp, dk_nws_ct, dk_ced, exp_unt, vld_from, vld_to, est_dat, own_id, rtl_expn_id, obf_m2m_mi_id, NULL mco_grp_id, NULL exp_pct
                           FROM t_rtl_expn) rx
                                ON rx.mco_id = sd.mco_id
                               AND rx.dk_org_cy_smp = sd.dk_org_cy
                               AND rx.dk_nws_ct = sd.dk_nws_ct
                               AND rx.dk_ced = sd.dk_ced
                               AND rx.obf_m2m_mi_id = js.obf_mmi_id
                               AND sd.dk_dat_mo BETWEEN MONTHS_BETWEEN(TRUNC(rx.vld_from,'MM'),TO_DATE('19891201','YYYYMMDD'))
                                                    AND MONTHS_BETWEEN(TRUNC(rx.vld_to,'MM'),TO_DATE('19891201','YYYYMMDD'))
                    CROSS JOIN (SELECT NULL fctr FROM DUAL) emo;     
                    
                    
SELECT cpy_id_mco mco_id, dk_org_cy_smp, dk_nws_ct, dk_ced, exp_unt, vld_from, vld_to, est_dat, own_id, rtl_expn_id, obf_m2m_mi_id, NULL mco_grp_id, NULL exp_pct
                           FROM t_rtl_expn;